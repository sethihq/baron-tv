package com.barontv.home.home

import android.animation.TimeInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import com.barontv.home.core.Tokens

/** Motion switches for the home screen's parts. */
object HomeMotion {
    /** Off in snapshot tests: every tween lands at once, so a frame shows the settled state. */
    @JvmField
    var enabled = true

    /** The second hand's tick, cubic-bezier(0.2, 0, 0, 1) as bento.css. */
    val TICK: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** The time animations run on: the frame's time, so every tween in a frame agrees. */
    fun now(): Long = AnimationUtils.currentAnimationTimeMillis()
}

/**
 * One value easing from wherever it is to a target, as a CSS transition does (a new target starts from
 * the current value). It holds no listeners and allocates nothing per frame: a view calls [step] in onDraw
 * and asks for another frame while it returns true.
 */
class Tween(initial: Float) {
    var value: Float = initial
        private set
    var target: Float = initial
        private set
    private var from = initial
    private var start = 0L
    private var duration = 0L
    private var ease: TimeInterpolator = Tokens.EASE_OUT

    val running: Boolean get() = value != target

    fun to(goal: Float, durationMs: Long, interpolator: TimeInterpolator = Tokens.EASE_OUT, now: Long = HomeMotion.now()) {
        if (goal == target && (running || value == goal)) return
        if (!HomeMotion.enabled || durationMs <= 0) {
            snap(goal)
            return
        }
        from = value
        target = goal
        start = now
        duration = durationMs
        ease = interpolator
    }

    fun snap(goal: Float) {
        from = goal
        target = goal
        value = goal
    }

    /** Moves the value to where it is at [now]; true while it's still on its way. */
    fun step(now: Long = HomeMotion.now()): Boolean {
        if (value == target) return false
        val t = if (duration <= 0) 1f else ((now - start).toFloat() / duration).coerceIn(0f, 1f)
        value = if (t >= 1f) target else from + (target - from) * ease.getInterpolation(t)
        return value != target
    }
}
