package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The touch pad's rules at one pixel per dp, on a pad of [W] by [H].
 *
 * Swipes are checked against phone.js: a 10 slop, a step every 56 of travel, a flick over 0.45 per
 * millisecond (or 0.6 of a step) counting once, and a tap under 500 ms. Presses are the D-pad: the edges
 * reach 72 in, a tap there sends its direction, holding there repeats on the volume keys' clock (the
 * first at 420 ms, then every 160), and a held finger may drift 24 further in.
 */
class PadGestureTest {
    private val pad = PadGesture(slop = 10f, step = 56f, flick = 0.45f, edge = 72f, holdSlack = 24f)

    private fun down(x: Float, y: Float, t: Long = 0): TvKey = pad.down(x, y, t, W, H)

    /** Moves in [n] even steps from (x0, y0) to (x1, y1) over [ms], collecting the keys it sends. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long, n: Int = 20, t0: Long = 0): List<TvKey> {
        val keys = mutableListOf<TvKey>()
        for (i in 1..n) {
            val f = i / n.toFloat()
            pad.move(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t0 + (ms * f).toLong())?.let { keys += it }
        }
        return keys
    }

    /**
     * Runs the clock as the screen does: whenever a held press is due, polls at that moment. Returns
     * when each press went, up to [until].
     */
    private fun holdUntil(until: Long): List<Long> {
        val times = mutableListOf<Long>()
        while (true) {
            val due = pad.dueAt ?: break
            if (due > until) break
            pad.poll(due)?.let { times += due }
        }
        return times
    }

    // Swipes, exactly as before the pad had edges.

    @Test
    fun aQuickTouchIsOk() {
        assertEquals(TvKey.OK, down(100f, 100f))
        assertEquals(TvKey.OK, pad.up(102f, 101f, 120))
    }

    @Test
    fun wobbleInsideTheSlopIsStillATap() {
        down(100f, 100f)
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 107f, 106f, ms = 80))
        assertEquals(TvKey.OK, pad.up(107f, 106f, 90))
    }

    @Test
    fun aLongStillTouchIsNothing() {
        down(100f, 100f)
        assertNull("the middle never repeats", pad.dueAt)
        assertNull(pad.up(100f, 100f, 600))
    }

    @Test
    fun everyStepOfTravelIsOnePress() {
        down(20f, 200f)
        // 180 of slow travel to the right: three whole steps, the 12 left over count for nothing.
        assertEquals(List(3) { TvKey.RIGHT }, drag(20f, 200f, 200f, 200f, ms = 1800, n = 180))
        assertNull(pad.up(200f, 200f, 1810))
    }

    @Test
    fun theLongerAxisWins() {
        down(100f, 100f)
        assertEquals(listOf(TvKey.DOWN), drag(100f, 100f, 140f, 160f, ms = 600, n = 60))
    }

    @Test
    fun eachStepMeasuresFromTheLastOne() {
        down(100f, 300f)
        assertEquals(listOf(TvKey.RIGHT), drag(100f, 300f, 160f, 300f, ms = 600, n = 60))
        // Turning upwards from there: up is measured from where the right step fired.
        assertEquals(listOf(TvKey.UP), drag(160f, 300f, 160f, 240f, ms = 600, n = 60, t0 = 600))
    }

    @Test
    fun aQuickShortSwipeCountsOnce() {
        down(100f, 100f)
        // 30 in 40 ms: past the slop, short of a step, fast.
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 70f, 100f, ms = 40, n = 4))
        assertEquals(TvKey.LEFT, pad.up(70f, 100f, 45))
    }

    @Test
    fun aSlowShortSwipeIsNothing() {
        down(100f, 100f)
        // 30 over 300 ms: too slow for a flick and under 0.6 of a step.
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 100f, 130f, ms = 300, n = 30))
        assertNull(pad.up(100f, 130f, 310))
    }

    @Test
    fun aSlowSwipeOfMostOfAStepCountsOnce() {
        down(100f, 100f)
        // 40 over 400 ms is slow, but past 0.6 of a step (33.6).
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 100f, 60f, ms = 400, n = 40))
        assertEquals(TvKey.UP, pad.up(100f, 60f, 410))
    }

    @Test
    fun aSwipeThatSteppedAddsNothingOnRelease() {
        down(100f, 100f)
        assertEquals(listOf(TvKey.RIGHT), drag(100f, 100f, 170f, 100f, ms = 70, n = 7))
        assertNull(pad.up(170f, 100f, 72))
    }

    @Test
    fun aCancelledTouchSendsNothing() {
        down(100f, 100f)
        pad.cancel()
        assertNull(pad.up(100f, 100f, 50))
        assertNull(pad.move(300f, 100f, 60))
    }

    @Test
    fun directionsMatchThePrototype() {
        assertEquals(TvKey.RIGHT, directionOf(10f, 5f))
        assertEquals(TvKey.LEFT, directionOf(-10f, 5f))
        assertEquals(TvKey.DOWN, directionOf(5f, 10f))
        assertEquals(TvKey.UP, directionOf(5f, -10f))
        // A perfect diagonal goes up or down, as `Math.abs(dx) > Math.abs(dy)` does.
        assertEquals(TvKey.DOWN, directionOf(10f, 10f))
    }

    // The edges, a D-pad.

    @Test
    fun aTapOnEachCaretSendsItsDirectionOnRelease() {
        // Where the carets are: 26 in from the middle of each edge.
        val carets = mapOf(
            TvKey.UP to (W / 2 to 26f),
            TvKey.DOWN to (W / 2 to H - 26f),
            TvKey.LEFT to (26f to H / 2),
            TvKey.RIGHT to (W - 26f to H / 2),
        )
        for ((key, at) in carets) {
            val (x, y) = at
            assertEquals(key, down(x, y))
            assertEquals(key, pad.armed)
            assertNull("nothing goes until the finger lifts", pad.move(x + 2f, y + 1f, 40))
            assertEquals(key, pad.up(x + 2f, y + 1f, 90))
            assertNull(pad.armed)
        }
    }

    @Test
    fun aSlowTapOnAnEdgeStillCountsOnce() {
        down(W - 20f, H / 2)
        // 400 ms is too long for OK in the middle, but on an edge a press is a press.
        assertEquals(emptyList<Long>(), holdUntil(400))
        assertEquals(TvKey.RIGHT, pad.up(W - 20f, H / 2, 400))
    }

    @Test
    fun holdingAnEdgeRepeatsOnTheVolumeKeysClock() {
        down(W / 2, H - 30f)
        assertEquals(HoldRepeat.HOLD_MS, pad.dueAt)
        assertNull("nothing is due before 420 ms", pad.poll(419))
        assertEquals(TvKey.DOWN, pad.poll(420))
        assertEquals(580L, pad.dueAt)
        assertEquals(TvKey.DOWN, pad.poll(580))
        // Then every 160 ms, as the volume keys repeat from their 580 ms on.
        assertEquals(listOf(740L, 900L, 1060L, 1220L), holdUntil(1300))
        assertNull("the hold has sent its presses already", pad.up(W / 2, H - 30f, 1300))
        assertNull(pad.dueAt)
    }

    @Test
    fun liftingJustAfterTheFirstHeldPressSendsNothingMore() {
        down(30f, H / 2)
        assertEquals(TvKey.LEFT, pad.poll(420))
        assertNull(pad.up(30f, H / 2, 450))
    }

    @Test
    fun aLateClockSkipsMissedBeatsInsteadOfBursting() {
        down(W - 30f, H / 2)
        assertEquals(TvKey.RIGHT, pad.poll(420))
        // The phone was busy until 1000: one press now, and the next on the same beat after it.
        assertEquals(TvKey.RIGHT, pad.poll(1000))
        assertNull(pad.poll(1000))
        assertEquals(1060L, pad.dueAt)
    }

    @Test
    fun aHeldFingerMayWobbleAndDriftAlongItsEdge() {
        down(W - 30f, H / 2)
        // Inside the slop before the hold starts, so still a press.
        assertEquals(emptyList<TvKey>(), drag(W - 30f, H / 2, W - 24f, H / 2 + 6f, ms = 200, n = 10))
        assertEquals(TvKey.RIGHT, pad.poll(420))
        // Once it holds, the finger may wander along the edge and up to 24 further in.
        assertEquals(emptyList<TvKey>(), drag(W - 24f, H / 2 + 6f, W - 90f, H / 2 + 80f, ms = 300, n = 30, t0 = 420))
        assertEquals(TvKey.RIGHT, pad.armed)
        assertEquals(listOf(580L, 740L, 900L), holdUntil(1000))
    }

    @Test
    fun slidingOffAHeldEdgeEndsTheTouch() {
        down(W - 30f, H / 2)
        assertEquals(TvKey.RIGHT, pad.poll(420))
        // Past 72 + 24 from the right edge: the hold lets go, and the rest of the touch is no swipe.
        assertEquals(emptyList<TvKey>(), drag(W - 30f, H / 2, 40f, H / 2, ms = 400, n = 40, t0 = 450))
        assertNull(pad.armed)
        assertNull(pad.dueAt)
        assertNull(pad.up(40f, H / 2, 900))
    }

    @Test
    fun aSwipeThatStartsOnAnEdgeIsJustASwipe() {
        assertEquals(TvKey.RIGHT, down(W - 20f, H / 2))
        // Leftwards, quickly: past the slop before the hold could start.
        assertEquals(listOf(TvKey.LEFT, TvKey.LEFT), drag(W - 20f, H / 2, W - 140f, H / 2, ms = 240, n = 24))
        assertNull("no hold, and no edge press", pad.dueAt)
        assertNull(pad.armed)
        assertNull(pad.poll(1000))
        assertNull(pad.up(W - 140f, H / 2, 250))
    }

    @Test
    fun aFlickFromAnEdgeIsTheFlickNotTheEdge() {
        down(W / 2, 20f)
        // 30 down in 40 ms from the top edge: a flick downwards, not a press of Up.
        assertEquals(emptyList<TvKey>(), drag(W / 2, 20f, W / 2, 50f, ms = 40, n = 4))
        assertEquals(TvKey.DOWN, pad.up(W / 2, 50f, 45))
    }

    @Test
    fun aCancelledHoldStops() {
        down(W / 2, 20f)
        assertEquals(TvKey.UP, pad.poll(420))
        pad.cancel()
        assertNull(pad.dueAt)
        assertNull(pad.poll(580))
        assertNull(pad.up(W / 2, 20f, 600))
    }

    @Test
    fun eachTouchStartsAfresh() {
        down(W - 30f, H / 2)
        assertEquals(TvKey.RIGHT, pad.poll(420))
        assertNull(pad.up(W - 30f, H / 2, 500))
        // The next touch, in the middle, is a plain tap again.
        assertEquals(TvKey.OK, down(W / 2, H / 2, 1000))
        assertNull(pad.dueAt)
        assertEquals(TvKey.OK, pad.up(W / 2, H / 2, 1080))
    }

    private companion object {
        /** About the pad a Pixel 6 gives the Remote tab, in dp. */
        const val W = 371f
        const val H = 442f
    }
}
