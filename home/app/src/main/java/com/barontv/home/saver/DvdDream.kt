package com.barontv.home.saver

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Choreographer
import com.barontv.home.music.CubicBezier
import kotlin.random.Random

/**
 * The DVD screensaver, as the prototype's: the old DVD logo bouncing on black, a new colour at every
 * bounce and a little pulse on an exact corner hit. Fullscreen and not interactive, so the first key
 * only wakes it. With the TV's animations off it jumps to a new spot every 10 s instead of gliding.
 *
 * On the TV: settings put secure screensaver_components com.barontv.home/.saver.DvdDream. Music mode keeps
 * the screen on while it shows, so this never starts over the edge light.
 */
class DvdDream : DreamService() {
    private val motion = DvdMotion(Random(System.nanoTime()))
    private val main = Handler(Looper.getMainLooper())
    private var view: DvdView? = null
    private var running = false
    private var startedAt = 0.0
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val jump = object : Runnable {
        override fun run() {
            motion.jump()
            view?.place(clock())
            main.postDelayed(this, DvdMotion.JUMP_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val v = DvdView(this, motion)
        view = v
        setContentView(v)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val v = view ?: return
        val now = clock()
        motion.start(now)
        v.place(now)
        running = true
        if (ValueAnimator.areAnimatorsEnabled()) {
            // It fades in over 600 ms, then glides.
            startedAt = now
            v.logo.alpha = 0f
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            v.logo.alpha = 1f
            main.postDelayed(jump, DvdMotion.JUMP_MS)
        }
    }

    override fun onDreamingStopped() {
        stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        stop()
        view = null
        super.onDetachedFromWindow()
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(jump)
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
        const val FADE_MS = 600.0

        fun clock(): Double = System.nanoTime() / 1e6
    }
}
