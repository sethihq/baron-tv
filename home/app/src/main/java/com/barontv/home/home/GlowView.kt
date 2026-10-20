package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The glow behind everything (glow.css "Refined"): light rising from below the dock in the focused
 * artwork's colour, a deep core and a darker mid tone in one wide ellipse centred below the screen, and a
 * faint second tone in the top-left corner. A fine grain sits on top so the long fades don't band.
 *
 * The prototype's gradients are drawn here as weight maps: each ellipse's falloff is a small alpha-only
 * bitmap, stretched over the screen and tinted by its paint's colour, the layers added together. So a
 * colour change only changes three paint colours: nothing is allocated or rebuilt per frame, and the view
 * redraws only while its colours ease (700 ms) or it fades (600 ms in, 300 ms out). The two ellipses never
 * overlap, so adding them equals the prototype's layering; mixing the core into the mid tone in sRGB
 * instead of OKLCH is within 2 levels of 255 for every colour tried.
 */
class GlowView(context: Context) : View(context) {

    private val shown get() = opacity.target > 0f
    private val opacity = Tween(0f)

    // The three colours as premultiplied OKLab plus alpha (L*a, A*a, B*a, a), for core, mid and far: where
    // they are now, where the ease started and where it's going. CSS transitions colours in OKLab.
    private val now = DoubleArray(12)
    private val start = DoubleArray(12)
    private val goal = DoubleArray(12)
    private var easeStart = 0L
    private var easing = false
    private var goalArt = 0
    private var hasGoal = false

    private val corePaint = layerPaint(Profiles.core)
    private val midPaint = layerPaint(Profiles.mid)
    private val farPaint = layerPaint(Profiles.far)
    private val grainPaint = Paint().apply {
        shader = BitmapShader(Profiles.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            // One noise cell per dp, as the prototype's 160 px tile is drawn at one CSS pixel per dp.
            setLocalMatrix(Matrix().apply { setScale(context.dp(1f), context.dp(1f)) })
        }
        // Multiply leaves black black, as the prototype's overlay does.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    // Where each ellipse can be non-black, so the fill stays off the rest of the screen.
    private val wideRect = RectF()
    private val farRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setWillNotDraw(false)
    }

    /** Glow in the colour of [art] (a vivid colour from [VividColor]); fades in if it was hidden. */
    fun show(art: Int) {
        if (!hasGoal || art != goalArt) {
            hasGoal = true
            goalArt = art
            setGoal(GlowColors.forArt(art))
        }
        if (!shown) opacity.to(1f, FADE_IN_MS)
        invalidate()
    }

    /** Fades out (focus went to the side dock). The colours stay for the next time it shows. */
    fun hide() {
        if (!shown) return
        opacity.to(0f, FADE_OUT_MS)
        invalidate()
    }

    val isShowing: Boolean get() = shown

    /** The art colour it's glowing in (or last glowed in, while hidden). */
    val artColor: Int get() = goalArt

    private fun setGoal(colors: GlowColors) {
        val t = HomeMotion.now()
        stepColors(t)
        now.copyInto(start)
        put(colors.core, 0)
        put(colors.mid, 4)
        put(colors.far, 8)
        if (HomeMotion.enabled) {
            easeStart = t
            easing = true
        } else {
            goal.copyInto(now)
            easing = false
        }
    }

    private fun put(lch: DoubleArray, at: Int) {
        val alpha = lch[3]
        val h = Math.toRadians(lch[2])
        goal[at] = lch[0] * alpha
        goal[at + 1] = lch[1] * cos(h) * alpha
        goal[at + 2] = lch[1] * sin(h) * alpha
        goal[at + 3] = alpha
    }

    private fun stepColors(t: Long): Boolean {
        if (!easing) return false
        val p = ((t - easeStart).toFloat() / COLOR_MS).coerceIn(0f, 1f)
        val e = Tokens.EASE_OUT.getInterpolation(p).toDouble()
        for (i in 0 until 12) now[i] = start[i] + (goal[i] - start[i]) * e
        if (p >= 1f) easing = false
        return easing
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // radial-gradient(115% 74% at 50% 112%, core, mid 44%, transparent 80%): the map covers the
        // ellipse out to 80%, where it ends.
        place(corePaint, w * 0.5f, h * 1.12f, w * 1.15f * 0.8f, h * 0.74f * 0.8f, wideRect, w, h)
        place(midPaint, w * 0.5f, h * 1.12f, w * 1.15f * 0.8f, h * 0.74f * 0.8f, wideRect, w, h)
        // radial-gradient(60% 62% at 6% -10%, far, transparent 72%).
        place(farPaint, w * 0.06f, h * -0.10f, w * 0.60f * 0.72f, h * 0.62f * 0.72f, farRect, w, h)
    }

    private fun place(paint: Paint, cx: Float, cy: Float, rx: Float, ry: Float, bounds: RectF, w: Int, h: Int) {
        val side = Profiles.SIDE.toFloat()
        val m = Matrix()
        m.setScale(2 * rx / side, 2 * ry / side)
        m.postTranslate(cx - rx, cy - ry)
        paint.shader.setLocalMatrix(m)
        bounds.set(cx - rx, cy - ry, cx + rx, cy + ry)
        if (!bounds.intersect(0f, 0f, w.toFloat(), h.toFloat())) bounds.setEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        val t = HomeMotion.now()
        val moving = opacity.step(t) or stepColors(t)
        val o = opacity.value
        if (o > 0f) {
            if (tint(farPaint, 8, o)) canvas.drawRect(farRect, farPaint)
            val core = tint(corePaint, 0, o)
            val mid = tint(midPaint, 4, o)
            if (core) canvas.drawRect(wideRect, corePaint)
            if (mid) canvas.drawRect(wideRect, midPaint)
            canvas.drawRect(wideRect, grainPaint)
            canvas.drawRect(farRect, grainPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }

    /** Sets [paint] to colour [at] in sRGB at opacity [o]; false if it's fully transparent. */
    private fun tint(paint: Paint, at: Int, o: Float): Boolean {
        val a = now[at + 3]
        if (a <= 0.0) return false
        val alpha = (a * o * GRAIN_LIFT * 255).roundToInt().coerceIn(0, 255)
        if (alpha == 0) return false
        val rgb = Oklab.toArgb(now[at] / a, now[at + 1] / a, now[at + 2] / a)
        paint.color = (rgb and 0xFFFFFF) or (alpha shl 24)
        return true
    }

    override fun hasOverlappingRendering() = false

    private fun layerPaint(map: Bitmap) = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        shader = BitmapShader(map, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    /** The falloff maps and the grain tile: made once and shared, 64 KB in all. */
    private object Profiles {
        const val SIDE = 128

        // The core fades into the mid tone by 44%, the mid tone out by 80% (both on the map's 0.8 scale).
        val core: Bitmap = falloff(0.8f) { t -> 1f - t / 0.44f }
        val mid: Bitmap = falloff(0.8f) { t -> if (t <= 0.44f) t / 0.44f else (0.8f - t) / 0.36f }
        val far: Bitmap = falloff(0.72f) { t -> 1f - t / 0.72f }
        val grain: Bitmap = grainTile()

        /** An alpha-only map of [weight] over an ellipse whose edge is [extent] of the gradient's size. */
        private fun falloff(extent: Float, weight: (Float) -> Float): Bitmap {
            val bytes = ByteArray(SIDE * SIDE)
            for (y in 0 until SIDE) {
                val v = (y + 0.5f) / SIDE * 2f - 1f
                for (x in 0 until SIDE) {
                    val u = (x + 0.5f) / SIDE * 2f - 1f
                    val t = sqrt(u * u + v * v) * extent
                    val w = if (t >= extent) 0f else weight(t).coerceIn(0f, 1f)
                    bytes[y * SIDE + x] = (w * 255).roundToInt().toByte()
                }
            }
            return Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ALPHA_8).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }
        }

        /**
         * The fine grain: soft noise in a narrow band just under white, multiplied over the glow. With the
         * glow's alpha raised by [GRAIN_LIFT] to match, each pixel ends at glow x (1 + 0.042 x noise), which
         * is what the prototype's grey noise at 9% in overlay does to colours this dark.
         */
        private fun grainTile(): Bitmap {
            val random = Random(7)
            val raw = FloatArray(SIDE * SIDE) { random.nextFloat() }
            val pixels = IntArray(SIDE * SIDE)
            for (y in 0 until SIDE) {
                for (x in 0 until SIDE) {
                    // A 3 x 3 average softens single-pixel sparkle into grain, wrapping so the tile repeats.
                    var sum = 0f
                    for (dy in -1..1) for (dx in -1..1) sum += raw[((y + dy + SIDE) % SIDE) * SIDE + (x + dx + SIDE) % SIDE]
                    val noise = (0.5f + (sum / 9f - 0.5f) * 1.6f).coerceIn(0f, 1f)
                    val s = ((1f + GRAIN * noise) / GRAIN_LIFT * 255).roundToInt().coerceIn(0, 255)
                    pixels[y * SIDE + x] = (0xFF shl 24) or (s shl 16) or (s shl 8) or s
                }
            }
            return Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        }
    }

    companion object {
        const val COLOR_MS = 700L
        const val FADE_IN_MS = 600L
        const val FADE_OUT_MS = 300L

        /** The grain's strength: overlay of sRGB 0.735 grey (0.5 in linear light) at 9%. */
        private const val GRAIN = 0.47f * 0.09f
        private const val GRAIN_LIFT = 1f + GRAIN
    }
}
