package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns one finger on the touch pad into D-pad presses, exactly as the prototype's phone.js does:
 *
 * - The finger has to travel more than [slop] before the touch counts as a swipe.
 * - Then every [step] of travel, measured from where the last step fired, is one press in the
 *   direction it went most (the longer of across and down). The leftover travel starts afresh.
 * - A swipe too short for a step still counts once on release, if it was quick (faster than [flick]
 *   over the last few points) or went more than 0.6 of a step.
 * - A touch that never passed the slop and lifted within [TAP_MS] is a tap: OK. Held longer, it's nothing.
 *
 * Distances are in pixels and [flick] in pixels per millisecond, so the caller converts the prototype's
 * dp (10, 56 and 0.45) with the screen density. Times are the events' uptime in milliseconds.
 */
class PadGesture(
    private val slop: Float,
    private val step: Float,
    private val flick: Float,
) {
    private class Point(val x: Float, val y: Float, val t: Long)

    private var start: Point? = null
    private var origin: Point? = null
    private var moved = false
    private var stepped = false

    // The last few points, oldest first: a flick's speed is measured over them.
    private val recent = ArrayDeque<Point>(RECENT + 1)

    /** A finger came down: a new touch starts here. */
    fun down(x: Float, y: Float, t: Long) {
        val p = Point(x, y, t)
        start = p
        origin = p
        moved = false
        stepped = false
        recent.clear()
        recent.addLast(p)
    }

    /** The finger moved. Returns the direction to press when this move completes a step, else null. */
    fun move(x: Float, y: Float, t: Long): TvKey? {
        val s = start ?: return null
        val p = Point(x, y, t)
        recent.addLast(p)
        if (recent.size > RECENT) recent.removeFirst()
        if (!moved && hypot(x - s.x, y - s.y) > slop) moved = true
        if (!moved) return null
        val o = origin ?: return null
        val dx = x - o.x
        val dy = y - o.y
        if (max(abs(dx), abs(dy)) < step) return null
        stepped = true
        origin = p
        return directionOf(dx, dy)
    }

    /** The finger lifted. Returns [TvKey.OK] for a tap, a direction for a flick, or null. */
    fun up(x: Float, y: Float, t: Long): TvKey? {
        val s = start ?: return null
        start = null
        if (!moved) return if (t - s.t < TAP_MS) TvKey.OK else null
        if (stepped) return null
        val a = recent.first()
        val speed = hypot(x - a.x, y - a.y) / max(1L, t - a.t)
        val dx = x - s.x
        val dy = y - s.y
        return if (speed > flick || hypot(dx, dy) > step * FLICK_REACH) directionOf(dx, dy) else null
    }

    /** The system took the touch (or the screen went away): it ends with no press. */
    fun cancel() {
        start = null
    }

    companion object {
        /** The prototype's numbers, in dp and dp per millisecond. */
        const val SLOP_DP = 10f
        const val STEP_DP = 56f
        const val FLICK_DP_PER_MS = 0.45f

        /** A touch held longer than this without moving isn't a tap. */
        const val TAP_MS = 500L

        private const val RECENT = 6
        private const val FLICK_REACH = 0.6f
    }
}

/** The direction a move of (dx, dy) went most; ties between across and down count as down or up. */
internal fun directionOf(dx: Float, dy: Float): TvKey = when {
    abs(dx) > abs(dy) -> if (dx > 0) TvKey.RIGHT else TvKey.LEFT
    dy > 0 -> TvKey.DOWN
    else -> TvKey.UP
}
