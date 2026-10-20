package com.barontv.home.volume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.barontv.home.core.Tokens

/**
 * The home's volume bar over whatever is on screen, in place of the TV's own pop-up: VolumeBarView in an
 * accessibility overlay window (no extra permission, never focusable or touchable) exactly where the side
 * dock sits. It slides in from the right as the dock's Sound view does, stays [STAY_MS] after the last
 * change, then slides out. The window exists only while the bar shows: hidden, there's no window, no
 * frame and no animator running. Main thread only.
 */
internal class VolumeOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var bar: VolumeBarView? = null
    private var added = false
    private var leaving = false
    private var released = false
    private var slidePx = 0f
    private val hideLater = Runnable { leave() }

    // The slide, as side/SideKit.kt's Slide: opacity and position each with their own duration and ease.
    private val motion = ValueAnimator.ofFloat(0f, 1f).apply { interpolator = LinearInterpolator() }
    private var a0 = 0f
    private var a1 = 0f
    private var x0 = 0f
    private var x1 = 0f
    private var alphaMs = 1L
    private var moveMs = 1L

    init {
        motion.addUpdateListener { step(it.animatedFraction * maxOf(alphaMs, moveMs)) }
        motion.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationStart(animation: Animator) {
                cancelled = false
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                bar?.setLayerType(View.LAYER_TYPE_NONE, null)
                if (leaving && !cancelled) remove()
            }
        })
    }

    /** Shows [level], sliding in if the bar isn't up, and keeps it up [STAY_MS] from now. */
    fun show(level: VolumeLevel) {
        if (released) return
        main.removeCallbacks(hideLater)
        val wasUp = added
        if (!added && !add()) return
        val b = bar ?: return
        // On screen already (even on its way out), the fill slides to the new level; coming in, it's there.
        b.setLevel(level, animate = wasUp)
        if (!wasUp || leaving) {
            leaving = false
            // In: opacity over 240 ms and into place over 280, as the dock's views come in.
            go(1f, 0f, 240, 280)
        }
        main.postDelayed(hideLater, STAY_MS)
    }

    /** The service is going: the bar goes at once, and nothing shows it again. */
    fun release() {
        released = true
        main.removeCallbacks(hideLater)
        motion.removeAllListeners()
        motion.removeAllUpdateListeners()
        motion.cancel()
        remove()
        root = null
        bar = null
    }

    private fun add(): Boolean {
        val wm = windows ?: return false
        val screen = Point()
        // The display's own size, whatever the context's resources make of it (Android 9's call; the newer
        // window metrics only arrived in Android 11).
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(screen)
        val box = VolumePlace.box(screen.x, screen.y)
        val slide = VolumePlace.slide(screen.x)
        val r = root ?: FrameLayout(context).also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root = it
        }
        val b = bar ?: VolumeBarView(context).also {
            r.addView(it)
            bar = it
        }
        b.layoutParams = FrameLayout.LayoutParams(box.width, box.height)
        // Waiting, out of sight, a slide's width to the right.
        slidePx = slide.toFloat()
        b.alpha = 0f
        b.translationX = slidePx
        try {
            wm.addView(r, layoutParams(box, slide))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't show the volume bar: $e")
            return false
        }
        added = true
        return true
    }

    private fun leave() {
        if (!added || leaving) return
        leaving = true
        // Out: opacity over 220 ms, back to the right over 260, as the dock's views leave.
        go(0f, slidePx, 220, 260)
    }

    private fun go(alpha: Float, x: Float, alphaMs: Long, moveMs: Long) {
        val b = bar ?: return
        motion.cancel()
        a0 = b.alpha
        x0 = b.translationX
        a1 = alpha
        x1 = x
        this.alphaMs = alphaMs
        this.moveMs = moveMs
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Animations off on the TV: the bar just appears and goes.
            step(Float.MAX_VALUE)
            if (leaving) remove()
            return
        }
        // One texture moves and fades instead of the bar redrawing every frame.
        b.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        motion.duration = maxOf(alphaMs, moveMs)
        motion.start()
    }

    private fun step(ms: Float) {
        val b = bar ?: return
        val pa = Tokens.EASE_OUT.getInterpolation((ms / alphaMs).coerceIn(0f, 1f))
        val px = Tokens.EASE_OUT.getInterpolation((ms / moveMs).coerceIn(0f, 1f))
        b.alpha = a0 + (a1 - a0) * pa
        b.translationX = x0 + (x1 - x0) * px
    }

    private fun remove() {
        main.removeCallbacks(hideLater)
        leaving = false
        bar?.let {
            it.settle()
            it.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        val r = root
        if (added && r != null) {
            try {
                windows?.removeView(r)
            } catch (e: RuntimeException) {
                // Already gone with the service.
            }
        }
        added = false
    }

    // The box plus the slide's width to its right, over everything, never taking focus or touches (the
    // remote's keys come through the service).
    private fun layoutParams(box: VolumePlace.Box, slide: Int) = WindowManager.LayoutParams(
        box.width + slide,
        box.height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = box.left
        y = box.top
        title = "Volume"
    }

    private companion object {
        const val TAG = "BaronVolume"

        /** How long the bar stays after the last change. */
        const val STAY_MS = 2000L
    }
}
