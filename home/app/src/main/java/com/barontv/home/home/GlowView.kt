package com.barontv.home.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The glow behind everything (glow.css "Refined"): light rising from below the dock in the focused
 * artwork's colour, a deep core and a darker mid tone in one wide ellipse centred below the screen, and a
 * faint second tone in the top-left corner. A fine grain sits on top so the long fades don't band.
 *
 * Both ellipses are radial gradients stretched by a local matrix, as the CSS's are. The TV draws with
 * Android 9's older OpenGL renderer (dumpsys gfxinfo: Pipeline=FrameBuilder), which handles gradient
 * shaders but not alpha-only bitmaps inside a shader (those come out black), so no weight maps here. The
 * gradients are rebuilt only while the colours ease (700 ms); the fades (600 ms in, 300 ms out) only change
 * the paints' alpha. Mixing the core into the mid tone in sRGB instead of OKLCH is within 2 levels of 255.
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

    private val widePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val farPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    // Where each ellipse is centred and how it's stretched (the gradients are circles of radius rx).
    private var wideCx = 0f
    private var wideCy = 0f
    private var wideRx = 1f
    private val wideMatrix = Matrix()
    private var farCx = 0f
    private var farCy = 0f
    private var farRx = 1f
    private val farMatrix = Matrix()

    // The colours the gradients were last built with, so they're only rebuilt when the colours move.
    private val wideColors = IntArray(3)
    private val farColors = IntArray(2)
    private var builtCore = 0
    private var builtMid = 0
    private var builtFar = 0
    private var built = false
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
        // radial-gradient(115% 74% at 50% 112%, core, mid 44%, transparent 80%).
        wideCx = w * 0.5f
        wideCy = h * 1.12f
        wideRx = w * 1.15f
        place(wideMatrix, wideCx, wideCy, wideRx, h * 0.74f, 0.80f, wideRect, w, h)
        // radial-gradient(60% 62% at 6% -10%, far, transparent 72%).
        farCx = w * 0.06f
        farCy = h * -0.10f
        farRx = w * 0.60f
        place(farMatrix, farCx, farCy, farRx, h * 0.62f, 0.72f, farRect, w, h)
        built = false
    }

    /** Squashes a circle of radius [rx] into the CSS ellipse, and bounds what it lights out to [extent]. */
    private fun place(m: Matrix, cx: Float, cy: Float, rx: Float, ry: Float, extent: Float, bounds: RectF, w: Int, h: Int) {
        m.setScale(1f, ry / rx, cx, cy)
        bounds.set(cx - rx * extent, cy - ry * extent, cx + rx * extent, cy + ry * extent)
        if (!bounds.intersect(0f, 0f, w.toFloat(), h.toFloat())) bounds.setEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        val t = HomeMotion.now()
        val moving = opacity.step(t) or stepColors(t)
        val o = opacity.value
        if (o > 0f && hasGoal) {
            rebuildIfMoved()
            val alpha = (o * 255).roundToInt().coerceIn(0, 255)
            widePaint.alpha = alpha
            farPaint.alpha = alpha
            if (!farRect.isEmpty) canvas.drawRect(farRect, farPaint)
            if (!wideRect.isEmpty) canvas.drawRect(wideRect, widePaint)
            canvas.drawRect(wideRect, grainPaint)
            canvas.drawRect(farRect, grainPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }

    /** New gradients only when a colour has moved since the last ones (while easing, or the first time). */
    private fun rebuildIfMoved() {
        val core = colorAt(0)
        val mid = colorAt(4)
        val far = colorAt(8)
        if (built && core == builtCore && mid == builtMid && far == builtFar) return
        built = true
        builtCore = core
        builtMid = mid
        builtFar = far
        // Each ends in its own colour at no alpha, so the fade out doesn't darken on the way, as CSS's
        // premultiplied fades don't.
        wideColors[0] = core
        wideColors[1] = mid
        wideColors[2] = mid and 0xFFFFFF
        widePaint.shader = RadialGradient(wideCx, wideCy, wideRx, wideColors, WIDE_STOPS, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(wideMatrix) }
        farColors[0] = far
        farColors[1] = far and 0xFFFFFF
        farPaint.shader = RadialGradient(farCx, farCy, farRx, farColors, FAR_STOPS, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(farMatrix) }
    }

    /** Colour [at] in sRGB with its own alpha (raised to make up for the grain's multiply). */
    private fun colorAt(at: Int): Int {
        val a = now[at + 3]
        if (a <= 0.0) return 0
        val alpha = (a * GRAIN_LIFT * 255).roundToInt().coerceIn(0, 255)
        val rgb = Oklab.toArgb(now[at] / a, now[at + 1] / a, now[at + 2] / a)
        return (rgb and 0xFFFFFF) or (alpha shl 24)
    }

    override fun hasOverlappingRendering() = false

    /** The grain tile: made once and shared, 64 KB. */
    private object Profiles {
        const val SIDE = 128

        val grain: Bitmap = grainTile()

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

        private val WIDE_STOPS = floatArrayOf(0f, 0.44f, 0.80f)
        private val FAR_STOPS = floatArrayOf(0f, 0.72f)
    }
}
