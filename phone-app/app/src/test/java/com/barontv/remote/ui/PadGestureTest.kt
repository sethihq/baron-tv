package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The touch pad's rules, checked against phone.js at one pixel per dp: a 10 slop, a step every 56 of
 * travel, a flick over 0.45 per millisecond (or 0.6 of a step) counting once, and a tap under 500 ms.
 */
class PadGestureTest {
    private val pad = PadGesture(slop = 10f, step = 56f, flick = 0.45f)

    /** Moves in [n] even steps from (x0, y0) to (x1, y1) over [ms], collecting the keys it sends. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long, n: Int = 20, t0: Long = 0): List<TvKey> {
        val keys = mutableListOf<TvKey>()
        for (i in 1..n) {
            val f = i / n.toFloat()
            pad.move(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t0 + (ms * f).toLong())?.let { keys += it }
        }
        return keys
    }

    @Test
    fun aQuickTouchIsOk() {
        pad.down(100f, 100f, 0)
        assertEquals(TvKey.OK, pad.up(102f, 101f, 120))
    }

    @Test
    fun wobbleInsideTheSlopIsStillATap() {
        pad.down(100f, 100f, 0)
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 107f, 106f, ms = 80))
        assertEquals(TvKey.OK, pad.up(107f, 106f, 90))
    }

    @Test
    fun aLongStillTouchIsNothing() {
        pad.down(100f, 100f, 0)
        assertNull(pad.up(100f, 100f, 600))
    }

    @Test
    fun everyStepOfTravelIsOnePress() {
        pad.down(20f, 200f, 0)
        // 180 of slow travel to the right: three whole steps, the 12 left over count for nothing.
        assertEquals(List(3) { TvKey.RIGHT }, drag(20f, 200f, 200f, 200f, ms = 1800, n = 180))
        assertNull(pad.up(200f, 200f, 1810))
    }

    @Test
    fun theLongerAxisWins() {
        pad.down(100f, 100f, 0)
        assertEquals(listOf(TvKey.DOWN), drag(100f, 100f, 140f, 160f, ms = 600, n = 60))
    }

    @Test
    fun eachStepMeasuresFromTheLastOne() {
        pad.down(100f, 300f, 0)
        assertEquals(listOf(TvKey.RIGHT), drag(100f, 300f, 160f, 300f, ms = 600, n = 60))
        // Turning upwards from there: up is measured from where the right step fired.
        assertEquals(listOf(TvKey.UP), drag(160f, 300f, 160f, 240f, ms = 600, n = 60, t0 = 600))
    }

    @Test
    fun aQuickShortSwipeCountsOnce() {
        pad.down(100f, 100f, 0)
        // 30 in 40 ms: past the slop, short of a step, fast.
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 70f, 100f, ms = 40, n = 4))
        assertEquals(TvKey.LEFT, pad.up(70f, 100f, 45))
    }

    @Test
    fun aSlowShortSwipeIsNothing() {
        pad.down(100f, 100f, 0)
        // 30 over 300 ms: too slow for a flick and under 0.6 of a step.
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 100f, 130f, ms = 300, n = 30))
        assertNull(pad.up(100f, 130f, 310))
    }

    @Test
    fun aSlowSwipeOfMostOfAStepCountsOnce() {
        pad.down(100f, 100f, 0)
        // 40 over 400 ms is slow, but past 0.6 of a step (33.6).
        assertEquals(emptyList<TvKey>(), drag(100f, 100f, 100f, 60f, ms = 400, n = 40))
        assertEquals(TvKey.UP, pad.up(100f, 60f, 410))
    }

    @Test
    fun aSwipeThatSteppedAddsNothingOnRelease() {
        pad.down(100f, 100f, 0)
        assertEquals(listOf(TvKey.RIGHT), drag(100f, 100f, 170f, 100f, ms = 70, n = 7))
        assertNull(pad.up(170f, 100f, 72))
    }

    @Test
    fun aCancelledTouchSendsNothing() {
        pad.down(100f, 100f, 0)
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
}
