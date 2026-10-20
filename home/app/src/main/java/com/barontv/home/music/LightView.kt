package com.barontv.home.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the edge light: every dab is the same small pre-blurred alpha bitmap, drawn with drawBitmap so
 * the paint's colour tints it (the TV's OpenGL renderer draws an alpha bitmap inside a BitmapShader as
 * nothing), and added onto the others (ADD, the prototype's "lighter"). The view is the prototype's
 * half-resolution canvas, 480 x 270 pixels, on a hardware layer that MusicLayer scales up to the screen,
 * so the GPU fills a quarter of the pixels and the scaling's filtering softens it as the CSS did.
 *
 * The canvas had blur(2.5px): the blur is in the dab, and its one visible effect, the light easing off
 * over the outermost few pixels where the blur took in nothing from beyond the screen, is four thin
 * gradients multiplied over the edges (DST_IN, inside the layer). No allocations per frame, and no
 * saveLayer on the TV.
 */
@SuppressLint("ViewConstructor")
internal class LightView(context: Context, private val light: EdgeLight) : View(context) {
    private val dab = makeDab()
    private val src = Rect(0, 0, DAB_PX, DAB_PX)
    private val dst = RectF()
    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val edge = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val strips = arrayOf(
        RectF(0f, 0f, EdgeLight.W.toFloat(), EDGE_PX),
        RectF(0f, EdgeLight.H.toFloat() - EDGE_PX, EdgeLight.W.toFloat(), EdgeLight.H.toFloat()),
        RectF(0f, 0f, EDGE_PX, EdgeLight.H.toFloat()),
        RectF(EdgeLight.W.toFloat() - EDGE_PX, 0f, EdgeLight.W.toFloat(), EdgeLight.H.toFloat()),
    )
    private val shaders = arrayOf(
        edgeShader(0f, 0f, 0f, EDGE_PX),
        edgeShader(0f, EdgeLight.H.toFloat(), 0f, EdgeLight.H.toFloat() - EDGE_PX),
        edgeShader(0f, 0f, EDGE_PX, 0f),
        edgeShader(EdgeLight.W.toFloat(), 0f, EdgeLight.W.toFloat() - EDGE_PX, 0f),
    )

    init {
        // On the TV the light renders into its own small layer; layoutlib (the snapshots) has no GPU
        // layers and draws it straight at full size instead.
        if (HALF_RES_LAYER && !isInEditMode) setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // Without a layer, its fade is applied draw by draw (added light scales the same) rather than through
    // an offscreen buffer every frame.
    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        // Software drawing (the snapshots) gets a layer to multiply the edges in; the TV never does this.
        val save = if (canvas.isHardwareAccelerated) -1 else canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val scale = DAB_PX / 2f / DAB_RADIUS
        for (i in 0 until light.dabs) {
            val r = light.radius[i] * scale
            val x = light.x[i]
            val y = light.y[i]
            dst.set(x - r, y - r, x + r, y + r)
            add.color = light.argb[i]
            canvas.drawBitmap(dab, src, dst, add)
        }
        // Only inside a layer: straight into the window, DST_IN would darken the home screen's edges
        // while it fades.
        if (HALF_RES_LAYER || save >= 0) {
            for (i in 0 until 4) {
                edge.shader = shaders[i]
                canvas.drawRect(strips[i], edge)
            }
        }
        if (save >= 0) canvas.restoreToCount(save)
    }

    fun release() {
        dab.recycle()
    }

    companion object {
        /**
         * The light in its own 480 x 270 hardware layer (a RenderNode layer, not saveLayer). If the TV's
         * renderer ever shows it wrong, false draws the dabs straight into the window at full resolution:
         * over the black root they add up the same, at about two and a half times the GPU fill.
         */
        const val HALF_RES_LAYER = true

        /** The dab bitmap: a disc of light [DAB_RADIUS] texels round, in a square with room for its blur. */
        const val DAB_PX = 64
        const val DAB_RADIUS = 28f

        /** The canvas blur, 2.5 CSS pixels: 1.25 light pixels, against a typical dab of 20 light pixels. */
        private const val BLUR = 1.25 / 20.0

        /** How far in from each edge the blur's easing off reaches, in light pixels. */
        private const val EDGE_PX = 4f

        /**
         * The prototype's dab: a radial gradient whose alpha runs 1, 0.85 at 0.3 of the way out, 0.28 at
         * 0.7, and 0 at the rim, then blurred. Its colour is the paint's.
         */
        fun makeDab(): Bitmap {
            val n = DAB_PX
            val c = n / 2f
            val raw = FloatArray(n * n)
            // Four samples a texel, so the disc's rim is smooth before the blur.
            for (py in 0 until n) {
                for (px in 0 until n) {
                    var sum = 0f
                    for (sy in 0 until 2) {
                        for (sx in 0 until 2) {
                            val dx = px + 0.25f + 0.5f * sx - c
                            val dy = py + 0.25f + 0.5f * sy - c
                            sum += profile(sqrt(dx * dx + dy * dy) / DAB_RADIUS)
                        }
                    }
                    raw[py * n + px] = sum / 4f
                }
            }
            val blurred = blur(raw, n, (BLUR * DAB_RADIUS).toFloat())
            val bytes = ByteArray(n * n) { (blurred[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
            return Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
        }

        fun profile(r: Float): Float = when {
            r >= 1f -> 0f
            r < 0.3f -> 1f - 0.15f * (r / 0.3f)
            r < 0.7f -> 0.85f - 0.57f * ((r - 0.3f) / 0.4f)
            else -> 0.28f * (1f - (r - 0.7f) / 0.3f)
        }

        // A separable Gaussian blur, once, when the bitmap is made.
        private fun blur(src: FloatArray, n: Int, sigma: Float): FloatArray {
            val reach = (sigma * 3).toInt().coerceAtLeast(1)
            val kernel = FloatArray(reach * 2 + 1) { exp(-((it - reach) * (it - reach)) / (2 * sigma * sigma)) }
            val total = kernel.sum()
            for (i in kernel.indices) kernel[i] /= total
            val tmp = FloatArray(n * n)
            val out = FloatArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f
                for (k in kernel.indices) {
                    val xx = x + k - reach
                    if (xx in 0 until n) s += src[y * n + xx] * kernel[k]
                }
                tmp[y * n + x] = s
            }
            for (y in 0 until n) for (x in 0 until n) {
                var s = 0f
                for (k in kernel.indices) {
                    val yy = y + k - reach
                    if (yy in 0 until n) s += tmp[yy * n + x] * kernel[k]
                }
                out[y * n + x] = s
            }
            return out
        }

        // From the edge inward: what a 1.25 pixel blur leaves of a light that stops at the screen's edge.
        private fun edgeShader(x0: Float, y0: Float, x1: Float, y1: Float) = LinearGradient(
            x0, y0, x1, y1,
            intArrayOf(alpha(0.5f), alpha(0.655f), alpha(0.885f), alpha(0.977f), alpha(1f)),
            floatArrayOf(0f, 0.125f, 0.375f, 0.625f, 1f),
            Shader.TileMode.CLAMP,
        )

        private fun alpha(a: Float) = (a * 255f).roundToInt() shl 24
    }
}
