package com.barontv.home.side

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.roundToInt

/** sidebar.css and bento.css colours that core's Tokens doesn't carry, converted from OKLCH exactly. */
internal object SideColors {
    const val ROW = 0xFF0D0D0D.toInt() // .sv-item and .sv-group: oklch 16%
    const val ROW_FOCUS = 0xFF333333.toInt() // oklch 32%
    const val SWITCH_OFF = 0xFF383838.toInt() // oklch 34%, also the joining badge
    const val SWITCH_OFF_FOCUS = 0xFF484848.toInt() // oklch 40%
    const val JOINING_PEAK = 0xFF585858.toInt() // oklch 46%: the joining badge breathes up to this
    const val GREEN = 0xFF2EB45C.toInt() // oklch(68% 0.17 150): the switch when on, the badge
    const val GREEN_FOCUS = 0xFF3FC168.toInt() // oklch(72% 0.17 150)
    const val KNOB = 0xFFF8F8F8.toInt() // oklch 98%
    const val BADGE_GLYPH = 0xFFFCFCFC.toInt() // oklch 99%
    const val RING = 0xE6FFFFFF.toInt() // the switch's focus ring: white at 90%
    const val HAIRLINE = 0x12FFFFFF // between grouped rows: white at 7%
    const val LEVEL_FOCUS = 0xFF222222.toInt() // the volume bar focused: oklch 25%
    const val LEVEL_FILL = 0xFFEEEEEE.toInt() // the level: oklch 95%
    const val ON_FILL = 0xFF0D0D0D.toInt() // text on the level: oklch 16%
    const val ON_FILL_CAPTION = 0x990D0D0D.toInt() // its caption: oklch 16% at 60%

    /** A colour part way from [c0] to [c1], for the 200 to 240 ms focus fades. */
    fun mix(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        fun ch(shift: Int): Int {
            val a = c0 ushr shift and 0xFF
            val b = c1 ushr shift and 0xFF
            return (a + (b - a) * t).roundToInt()
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Open Runde's ascent and descent as fractions of its size (2728 and 680 of 2816 units). A CSS line box
 * puts the baseline half the leftover leading below its top, plus the ascent.
 */
internal object Type {
    const val ASCENT = 2728f / 2816f
    const val DESCENT = 680f / 2816f

    /** The baseline's distance below the top of a CSS line box [lineHeight] tall, for text [size] (both dp). */
    fun baseline(lineHeight: Float, size: Float): Float = (lineHeight - (ASCENT + DESCENT) * size) / 2f + ASCENT * size

    fun paint(context: Context, face: Typeface, sizeDp: Float, color: Int, trackingEm: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face
            textSize = context.dp(sizeDp)
            this.color = color
            letterSpacing = trackingEm
        }
}

/** A Phosphor glyph of our own (mutated), tinted once. */
internal fun Context.glyph(res: Int, color: Int): Drawable = getDrawable(res)!!.mutate().apply { setTint(color) }

/**
 * One background thread for the side dock's calls into the system (TV inputs, Wi-Fi, volume, finding
 * the picture settings), which can block on a busy TV. Started on first use, let go when the dock is.
 * Once stopped it stays stopped (work posted later is dropped, never a new thread) until [start].
 */
internal class SideWorker {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var stopped = false
    val main = Handler(Looper.getMainLooper())

    /** The worker's handler, starting the thread on first use. */
    @Synchronized
    fun handler(): Handler = handler?.takeIf { stopped || thread?.isAlive == true } ?: begin()

    private fun begin(): Handler {
        val t = HandlerThread("side-dock", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        thread = t
        stopped = false
        return Handler(t.looper).also { handler = it }
    }

    fun post(task: () -> Unit) {
        handler().post(task)
    }

    fun toMain(task: () -> Unit) {
        main.post(task)
    }

    /** The dock is back on a window: a fresh thread if the last one was let go. */
    @Synchronized
    fun start() {
        if (stopped) begin()
    }

    /** Finishes what's queued, then lets the thread go. */
    @Synchronized
    fun stop() {
        thread?.quitSafely()
        stopped = true
    }
}

/**
 * sidebar.css's slide for one layer of the dock: it fades and moves sideways from wherever it is now, each
 * with its own duration after a shared delay, and hides itself once it has left. A hardware layer holds
 * it while it moves, so the fade costs one texture instead of redrawing every tile.
 */
internal class Slide(private val view: View) {
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply { interpolator = LinearInterpolator() }
    private var a0 = 1f
    private var a1 = 1f
    private var x0 = 0f
    private var x1 = 0f
    private var alphaMs = 1L
    private var moveMs = 1L
    private var delayMs = 0L
    private var hideAtEnd = false

    init {
        anim.addUpdateListener { step(it.animatedFraction * (delayMs + maxOf(alphaMs, moveMs))) }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                if (hideAtEnd) view.visibility = View.INVISIBLE
            }
        })
    }

    /** Moves to [alpha] and [x] px; [hide] makes it invisible once there. */
    fun to(alpha: Float, x: Float, alphaMs: Long, moveMs: Long, delayMs: Long, hide: Boolean, animate: Boolean) {
        anim.cancel()
        a0 = view.alpha
        x0 = view.translationX
        a1 = alpha
        x1 = x
        this.alphaMs = alphaMs
        this.moveMs = moveMs
        this.delayMs = delayMs
        hideAtEnd = hide
        if (!hide) view.visibility = View.VISIBLE
        if (!animate) {
            step(Float.MAX_VALUE)
            if (hide) view.visibility = View.INVISIBLE
            return
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        anim.duration = delayMs + maxOf(alphaMs, moveMs)
        anim.start()
    }

    private fun step(ms: Float) {
        val t = ms - delayMs
        val pa = Tokens.EASE_OUT.getInterpolation((t / alphaMs).coerceIn(0f, 1f))
        val px = Tokens.EASE_OUT.getInterpolation((t / moveMs).coerceIn(0f, 1f))
        view.alpha = a0 + (a1 - a0) * pa
        view.translationX = x0 + (x1 - x0) * px
    }
}
