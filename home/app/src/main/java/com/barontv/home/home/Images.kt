package com.barontv.home.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures at the size they're shown, never bigger: a poster or cover is decoded with the largest
 * inSampleSize that still covers its slot, then cropped and scaled to exactly the slot, as CSS's
 * object-fit: cover, into RGB_565 (dithered, so gradients don't band) when it has no transparency. A wide
 * poster on this TV is 416 x 255 px, about 210 KB; the whole screen's pictures come to well under 1 MB.
 *
 * Blocking work: call it off the main thread.
 */
object Images {
    /** Pictures shown recently, so a refresh or a recreated activity doesn't decode them again: 2 MB. */
    private val memory = object : LruCache<String, Bitmap>(2 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun remembered(key: String): Bitmap? = memory.get(key)?.takeUnless { it.isRecycled }

    fun remember(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    /** Lets the memory go when Android asks (the pictures on screen stay, held by their views). */
    fun trim() {
        memory.evictAll()
    }

    fun decodeFile(file: File, width: Int, height: Int, opaque: Boolean): Bitmap? =
        decode(width, height, opaque) { BitmapFactory.decodeFile(file.path, it) }

    fun decodeResource(res: Resources, id: Int, width: Int, height: Int, opaque: Boolean): Bitmap? =
        decode(width, height, opaque) { BitmapFactory.decodeResource(res, id, it) }

    private inline fun decode(width: Int, height: Int, opaque: Boolean, decoder: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decoder(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val full = decoder(options) ?: return null
        val fitted = cover(full, width, height, opaque)
        if (fitted !== full) full.recycle()
        return fitted
    }

    /** [src] cropped to [width] x [height]'s shape and scaled to it, as object-fit: cover. */
    fun cover(src: Bitmap, width: Int, height: Int, opaque: Boolean): Bitmap {
        val config = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        if (src.width == width && src.height == height && src.config == config) return src
        val scale = max(width.toFloat() / src.width, height.toFloat() / src.height)
        val sw = (width / scale).roundToInt().coerceIn(1, src.width)
        val sh = (height / scale).roundToInt().coerceIn(1, src.height)
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) / 2
        val out = Bitmap.createBitmap(width, height, config)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, width, height), paint)
        return out
    }

    /** An app's banner (any drawable) as a bitmap of exactly [width] x [height], cropped to fill. */
    fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = max(width.toFloat() / iw, height.toFloat() / ih)
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        drawable.setBounds(left, top, left + dw, top + dh)
        drawable.draw(Canvas(out))
        return out
    }

    /** Keeps a fitted picture on disk for the next boot: small JPEGs, since they're already slot-sized. */
    fun save(bitmap: Bitmap, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Couldn't save ${file.name}")
        }
    }

    /** A file name for a picture's URL at a size. */
    fun keyFor(url: String, width: Int, height: Int): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return "$hex-${width}x$height"
    }

    private const val HEX = "0123456789abcdef"
}
