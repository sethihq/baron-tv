package com.barontv.home.saver

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import com.barontv.home.R
import com.barontv.home.core.NowPlayingHub
import com.barontv.home.music.CubicBezier
import kotlin.random.Random

/**
 * The DVD screensaver inside the home screen. This TV's firmware has no dream service at all (`dumpsys
 * dreams`: "Can't find service: dreams"), so DvdDream never runs here and no screensaver ever has. Instead,
 * as in the prototype, the home screen starts it itself after [IDLE_MS] with no key, unless music is
 * playing: then idle time belongs to music mode, so the two never show together. The first key only wakes
 * it. It draws in [layer] (black), and hides what's behind so the GPU draws just the logo.
 */
class SaverOverlay(private val activity: Activity, private val layer: FrameLayout) {
    var isOn = false
        private set

    /** Told when it shows (true) and goes (false), so music mode stands aside and the clock rests. */
    var onShown: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val motion = DvdMotion(Random(System.nanoTime()))
    private var view: DvdView? = null
    private var foreground = false
    private var running = false
    private var startedAt = 0.0
    private val behind = arrayOfNulls<View>(BEHIND.size)
    private val startLater = Runnable { start() }
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val jump = object : Runnable {
        override fun run() {
            motion.jump()
            view?.place(clock())
            main.postDelayed(this, DvdMotion.JUMP_MS)
        }
    }

    init {
        layer.setBackgroundColor(Color.BLACK)
        layer.visibility = View.GONE
    }

    /** Any key or touch: restarts the idle clock. Returns true if it woke the screensaver (swallow it). */
    fun onUserActivity(): Boolean {
        val woke = isOn
        if (woke) stop()
        arm()
        return woke
    }

    /** The home screen is in front or not: the screensaver only counts down, and shows, while it is. */
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (foreground) arm() else {
            main.removeCallbacks(startLater)
            stop()
        }
    }

    /** Shows it now, without waiting (for checking it from adb; see HomeActivity). */
    fun showNow() {
        main.removeCallbacks(startLater)
        start()
    }

    fun release() {
        main.removeCallbacks(startLater)
        stop()
    }

    private fun arm() {
        main.removeCallbacks(startLater)
        if (foreground) main.postDelayed(startLater, IDLE_MS)
    }

    private fun start() {
        if (isOn || !foreground) return
        if (NowPlayingHub.current?.let { it.playing && it.isMusic } == true) {
            arm()
            return
        }
        val v = view ?: DvdView(activity, motion).also {
            view = it
            layer.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        isOn = true
        layer.visibility = View.VISIBLE
        for (i in BEHIND.indices) {
            behind[i] = activity.findViewById<View>(BEHIND[i])?.takeIf { it.visibility == View.VISIBLE }?.also { it.visibility = View.INVISIBLE }
        }
        onShown?.invoke(true)
        val now = clock()
        motion.start(now)
        v.place(now)
        running = true
        if (ValueAnimator.areAnimatorsEnabled()) {
            // The logo fades in over 600 ms, then glides.
            startedAt = now
            v.logo.alpha = 0f
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            v.logo.alpha = 1f
            main.postDelayed(jump, DvdMotion.JUMP_MS)
        }
    }

    private fun stop() {
        if (!isOn) return
        isOn = false
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(jump)
        layer.visibility = View.GONE
        for (i in BEHIND.indices) {
            behind[i]?.visibility = View.VISIBLE
            behind[i] = null
        }
        onShown?.invoke(false)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return
        val v = view ?: return
        val now = frameTimeNanos / 1e6
        motion.step(now)
        v.place(now)
        if (v.logo.alpha < 1f) v.logo.alpha = CubicBezier.EASE_OUT.at((now - startedAt) / FADE_MS).toFloat()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private companion object {
        /** The prototype's "Starts after" default: 10 minutes. */
        const val IDLE_MS = 10 * 60_000L
        const val FADE_MS = 600.0
        val BEHIND = intArrayOf(R.id.content, R.id.glowSlot, R.id.islandSlot)

        fun clock(): Double = System.nanoTime() / 1e6
    }
}
