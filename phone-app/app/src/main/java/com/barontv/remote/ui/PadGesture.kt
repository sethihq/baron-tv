package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns one finger on the touch pad into D-pad presses. The pad is a swipe pad and a D-pad at once.
 *
 * Swipes work exactly as the prototype's phone.js:
 * - The finger has to travel more than [slop] before the touch counts as a swipe.
 * - Then every [step] of travel, measured from where the last step fired, is one press in the
 *   direction it went most (the longer of across and down). The leftover travel starts afresh.
 * - A swipe too short for a step still counts once on release, if it was quick (faster than [flick]
 *   over the last few points) or went more than 0.6 of a step.
 *
 * A touch that stays inside the slop is a press, and where it lands decides what it sends ([padRegion]):
 * the four edges, where the carets sit, are the D-pad's arrows, and the middle is OK.
 * - Lifted on an edge: that direction, once.
 * - Held on an edge: that direction again and again, on the volume keys' clock ([HoldRepeat]). The
 *   first can't go the moment the finger lands, since the touch might still become a swipe, so it goes
 *   at 420 ms, then every 160 ms. It stops when the finger lifts or slides off that edge (it may wander
 *   [holdSlack] further in first), and a held touch never turns into a swipe.
 * - Lifted in the middle within [TAP_MS]: OK. Held longer, it's nothing, so a resting thumb sends nothing.
 *
 * Distances are in pixels and [flick] in pixels per millisecond, so the caller converts the dp below with
 * the screen density. Times are the events' uptime in milliseconds. Held presses come from [poll], which
 * the caller runs when [dueAt] comes round.
 */
class PadGesture(
    private val slop: Float,
    private val step: Float,
    private val flick: Float,
    private val edge: Float,
    private val holdSlack: Float,
) {
    private class Point(val x: Float, val y: Float, val t: Long)

    private var start: Point? = null
    private var origin: Point? = null
    private var width = 0f
    private var height = 0f

    /** What a press where the finger landed sends: an edge's direction, or OK in the middle. */
    private var region = TvKey.OK
    private var moved = false
    private var stepped = false
    private var holding = false

    /** The finger slid off the edge it held: nothing more comes from this touch. */
    private var spent = false
    private var due: Long? = null

    // The last few points, oldest first: a flick's speed is measured over them.
    private val recent = ArrayDeque<Point>(RECENT + 1)

    /** When [poll] next has a press to send for a held edge; null while no edge is held. */
    val dueAt: Long? get() = due

    /**
     * The edge a press would send right now: the one under a finger that hasn't moved, or the one being
     * held. Null in the middle, once the touch is a swipe, and between touches. The pad lights its caret.
     */
    val armed: TvKey?
        get() = region.takeIf { start != null && it != TvKey.OK && !moved && !spent }

    /**
     * A finger came down on a [width] by [height] pad: a new touch starts here. Returns what a press
     * here would send.
     */
    fun down(x: Float, y: Float, t: Long, width: Float, height: Float): TvKey {
        val p = Point(x, y, t)
        start = p
        origin = p
        this.width = width
        this.height = height
        region = padRegion(x, y, width, height, edge)
        moved = false
        stepped = false
        holding = false
        spent = false
        due = if (region == TvKey.OK) null else t + HoldRepeat.HOLD_MS
        recent.clear()
        recent.addLast(p)
        return region
    }

    /**
     * The clock reached [t] with no new event. Returns the held edge's direction when a press was due by
     * then, else null. Beats missed while the phone was busy are skipped rather than sent in a burst.
     */
    fun poll(t: Long): TvKey? {
        val d = due ?: return null
        if (start == null || t < d) return null
        holding = true
        val missed = (t - d) / HoldRepeat.EVERY_MS
        due = d + HoldRepeat.EVERY_MS * (missed + 1)
        return region
    }

    /** The finger moved. Returns the direction to press when this move completes a step, else null. */
    fun move(x: Float, y: Float, t: Long): TvKey? {
        val s = start ?: return null
        if (spent) return null
        if (holding) {
            if (padRegion(x, y, width, height, edge, holdSlack) != region) {
                holding = false
                spent = true
                due = null
            }
            return null
        }
        val p = Point(x, y, t)
        recent.addLast(p)
        if (recent.size > RECENT) recent.removeFirst()
        if (!moved && hypot(x - s.x, y - s.y) > slop) {
            moved = true
            due = null
        }
        if (!moved) return null
        val o = origin ?: return null
        val dx = x - o.x
        val dy = y - o.y
        if (max(abs(dx), abs(dy)) < step) return null
        stepped = true
        origin = p
        return directionOf(dx, dy)
    }

    /**
     * The finger lifted. Returns a direction for an edge tapped or a flick, [TvKey.OK] for a tap in the
     * middle, or null (a hold has already sent its presses).
     */
    fun up(x: Float, y: Float, t: Long): TvKey? {
        val s = start ?: return null
        val held = holding || spent
        cancel()
        if (held) return null
        if (!moved) {
            return when {
                // An edge tapped, or held so briefly its first held press hadn't gone yet: once either way.
                region != TvKey.OK -> region
                t - s.t < TAP_MS -> TvKey.OK
                else -> null
            }
        }
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
        holding = false
        spent = false
        due = null
    }

    companion object {
        /** The prototype's numbers, in dp and dp per millisecond. */
        const val SLOP_DP = 10f
        const val STEP_DP = 56f
        const val FLICK_DP_PER_MS = 0.45f

        /**
         * How far each edge reaches into the pad. The carets sit 12 to 40 dp in, and a thumb aimed at
         * one lands within about 30 dp of it, so 72 takes in every press meant for a caret and still
         * leaves the middle wide for OK (about 230 by 300 dp on a Pixel 6).
         */
        const val EDGE_DP = 72f

        /** How much further in a held finger may drift before its edge lets go. */
        const val HOLD_SLACK_DP = 24f

        /** A touch held longer than this in the middle without moving isn't a tap. */
        const val TAP_MS = 500L

        private const val RECENT = 6
        private const val FLICK_REACH = 0.6f
    }
}

/**
 * Hold to repeat, as phone.js's volume keys: a press, then after [HOLD_MS] a repeat every [EVERY_MS], so
 * repeats land at 580, 740, 900 ms and on. A held pad edge keeps the same clock; its first press comes at
 * [HOLD_MS] instead of at once, since until then the touch might still become a swipe.
 */
internal object HoldRepeat {
    const val HOLD_MS = 420L
    const val EVERY_MS = 160L
}

/** The share of the pad's shorter side an edge may take at most, so a small pad keeps a middle for OK. */
private const val MAX_EDGE_SHARE = 0.25f

/**
 * What a press at (x, y) on a [width] by [height] pad sends: the direction of the edge it's on, or
 * [TvKey.OK] in the middle. Each edge reaches [depth] into the pad, but never more than a quarter of its
 * shorter side, plus any [slack]. Where two edges meet, the nearer wins; an exact tie goes up or down, as
 * [directionOf] does.
 */
internal fun padRegion(x: Float, y: Float, width: Float, height: Float, depth: Float, slack: Float = 0f): TvKey {
    val reach = min(depth, min(width, height) * MAX_EDGE_SHARE) + slack
    val top = y
    val bottom = height - y
    val left = x
    val right = width - x
    val nearest = min(min(top, bottom), min(left, right))
    return when {
        nearest >= reach -> TvKey.OK
        nearest == top -> TvKey.UP
        nearest == bottom -> TvKey.DOWN
        nearest == left -> TvKey.LEFT
        else -> TvKey.RIGHT
    }
}

/** The direction a move of (dx, dy) went most; ties between across and down count as down or up. */
internal fun directionOf(dx: Float, dy: Float): TvKey = when {
    abs(dx) > abs(dy) -> if (dx > 0) TvKey.RIGHT else TvKey.LEFT
    dy > 0 -> TvKey.DOWN
    else -> TvKey.UP
}
