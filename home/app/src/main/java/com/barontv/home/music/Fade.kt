package com.barontv.home.music

import kotlin.math.abs

/**
 * A CSS cubic-bezier() timing function, solved for x the way browsers solve it (a few Newton steps, then
 * bisection), so fades here follow the same curves as the prototype's transitions. Plain Kotlin: Android's
 * PathInterpolator isn't there in JVM tests.
 */
internal class CubicBezier(x1: Double, y1: Double, x2: Double, y2: Double) {
    private val cx = 3.0 * x1
    private val bx = 3.0 * (x2 - x1) - cx
    private val ax = 1.0 - cx - bx
    private val cy = 3.0 * y1
    private val by = 3.0 * (y2 - y1) - cy
    private val ay = 1.0 - cy - by

    private fun curveX(t: Double) = ((ax * t + bx) * t + cx) * t

    private fun curveY(t: Double) = ((ay * t + by) * t + cy) * t

    private fun slopeX(t: Double) = (3.0 * ax * t + 2.0 * bx) * t + cx

    fun at(progress: Double): Double {
        if (progress <= 0.0) return 0.0
        if (progress >= 1.0) return 1.0
        var t = progress
        for (i in 0 until 8) {
            val err = curveX(t) - progress
            if (abs(err) < 1e-7) return curveY(t)
            val d = slopeX(t)
            if (abs(d) < 1e-6) break
            t -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        t = progress
        for (i in 0 until 40) {
            val x = curveX(t)
            if (abs(x - progress) < 1e-7) break
            if (x < progress) lo = t else hi = t
            t = (lo + hi) / 2.0
        }
        return curveY(t)
    }

    companion object {
        /** The prototype's ease everywhere: cubic-bezier(0.23, 1, 0.32, 1). */
        val EASE_OUT = CubicBezier(0.23, 1.0, 0.32, 1.0)
    }
}

/**
 * One opacity, moving as a CSS transition does: from wherever it is to [to], over a duration, after an
 * optional delay, on the prototype's ease-out. Times are milliseconds on the music clock.
 */
internal class Fade {
    var value = 0f
        private set
    var target = 0f
        private set
    private var from = 0f
    private var start = 0.0
    private var delay = 0.0
    private var duration = 0.0

    fun go(to: Float, now: Double, durationMs: Double, delayMs: Double = 0.0) {
        from = value
        target = to
        start = now
        delay = delayMs
        duration = durationMs
        if (durationMs <= 0.0 && delayMs <= 0.0) value = to
    }

    /** Straight to [to], no transition (reduced motion, or the home screen leaving). */
    fun jump(to: Float) {
        from = to
        target = to
        value = to
        duration = 0.0
        delay = 0.0
    }

    fun step(now: Double): Float {
        val t = now - start - delay
        value = when {
            t <= 0.0 -> from
            duration <= 0.0 || t >= duration -> target
            else -> from + (target - from) * CubicBezier.EASE_OUT.at(t / duration).toFloat()
        }
        return value
    }

    val settled: Boolean get() = value == target
}
