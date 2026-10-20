package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Phosphor glyphs (white vector drawables) turned once into small alpha masks, so they're drawn in any
 * colour by setting a paint's colour, as the prototype's CSS masks take the text colour: no tinting, no
 * allocation when a colour fades. A 20 dp glyph on this TV is 27 x 27 bytes.
 */
object Glyphs {
    private val masks = HashMap<Long, Bitmap>()

    /** The glyph [id] as a [sizePx] square alpha mask. Main thread (it's cached). */
    fun mask(context: Context, id: Int, sizePx: Int): Bitmap {
        val key = (id.toLong() shl 16) or sizePx.toLong()
        return masks.getOrPut(key) {
            val drawable = context.getDrawable(id)!!.mutate()
            val argb = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(argb))
            argb.extractAlpha().also { argb.recycle() }
        }
    }

    /** Draws [mask] with its top left at [x], [y] in [paint]'s colour. */
    fun draw(canvas: Canvas, mask: Bitmap, x: Float, y: Float, paint: Paint) {
        canvas.drawBitmap(mask, x, y, paint)
    }
}
