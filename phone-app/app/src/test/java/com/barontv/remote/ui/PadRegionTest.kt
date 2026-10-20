package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a press on the pad goes, at one pixel per dp: each edge reaches 72 in (at most a quarter of the
 * pad's shorter side) and sends its direction, the middle sends OK.
 */
class PadRegionTest {
    private fun at(x: Float, y: Float, w: Float = W, h: Float = H, slack: Float = 0f) =
        padRegion(x, y, w, h, depth = 72f, slack = slack)

    @Test
    fun theMiddleIsOk() {
        assertEquals(TvKey.OK, at(W / 2, H / 2))
        // The middle's four corners are still in it.
        assertEquals(TvKey.OK, at(72f, 72f))
        assertEquals(TvKey.OK, at(W - 72f, 72f))
        assertEquals(TvKey.OK, at(72f, H - 72f))
        assertEquals(TvKey.OK, at(W - 72f, H - 72f))
    }

    @Test
    fun eachEdgeReaches72In() {
        assertEquals(TvKey.UP, at(W / 2, 0f))
        assertEquals(TvKey.UP, at(W / 2, 71.9f))
        assertEquals(TvKey.OK, at(W / 2, 72f))

        assertEquals(TvKey.DOWN, at(W / 2, H))
        assertEquals(TvKey.DOWN, at(W / 2, H - 71.9f))
        assertEquals(TvKey.OK, at(W / 2, H - 72f))

        assertEquals(TvKey.LEFT, at(0f, H / 2))
        assertEquals(TvKey.LEFT, at(71.9f, H / 2))
        assertEquals(TvKey.OK, at(72f, H / 2))

        assertEquals(TvKey.RIGHT, at(W, H / 2))
        assertEquals(TvKey.RIGHT, at(W - 71.9f, H / 2))
        assertEquals(TvKey.OK, at(W - 72f, H / 2))
    }

    @Test
    fun aTapAnywhereOnACaretIsThatCaretsDirection() {
        // Each caret is a 28 box 12 in from the middle of its edge; try its corners and middle.
        for (inset in listOf(12f, 26f, 40f)) {
            for (along in listOf(-14f, 0f, 14f)) {
                assertEquals(TvKey.UP, at(W / 2 + along, inset))
                assertEquals(TvKey.DOWN, at(W / 2 + along, H - inset))
                assertEquals(TvKey.LEFT, at(inset, H / 2 + along))
                assertEquals(TvKey.RIGHT, at(W - inset, H / 2 + along))
            }
        }
    }

    @Test
    fun whereTwoEdgesMeetTheNearerWins() {
        assertEquals(TvKey.LEFT, at(10f, 40f))
        assertEquals(TvKey.UP, at(40f, 10f))
        assertEquals(TvKey.RIGHT, at(W - 10f, H - 40f))
        assertEquals(TvKey.DOWN, at(W - 40f, H - 10f))
        // An exact tie goes up or down, as a swipe's does.
        assertEquals(TvKey.UP, at(30f, 30f))
        assertEquals(TvKey.DOWN, at(W - 30f, H - 30f))
    }

    @Test
    fun aSmallPadKeepsAMiddleForOk() {
        // 320 by 200: an edge may take a quarter of 200, so 50, leaving a middle 220 by 100.
        assertEquals(TvKey.UP, at(160f, 49f, w = 320f, h = 200f))
        assertEquals(TvKey.OK, at(160f, 50f, w = 320f, h = 200f))
        assertEquals(TvKey.OK, at(160f, 100f, w = 320f, h = 200f))
        assertEquals(TvKey.OK, at(50f, 100f, w = 320f, h = 200f))
        assertEquals(TvKey.LEFT, at(49f, 100f, w = 320f, h = 200f))
    }

    @Test
    fun aHeldFingerGetsSlackBeforeItsEdgeLetsGo() {
        assertEquals(TvKey.OK, at(W - 90f, H / 2))
        assertEquals(TvKey.RIGHT, at(W - 90f, H / 2, slack = 24f))
        assertEquals(TvKey.OK, at(W - 96f, H / 2, slack = 24f))
    }

    @Test
    fun aFingerPastTheEdgeStillCountsForIt() {
        assertEquals(TvKey.RIGHT, at(W + 10f, H / 2))
        assertEquals(TvKey.LEFT, at(-5f, H / 2))
        assertEquals(TvKey.UP, at(W / 2, -8f))
        assertEquals(TvKey.DOWN, at(W / 2, H + 3f))
    }

    private companion object {
        /** About the pad a Pixel 6 gives the Remote tab, in dp. */
        const val W = 371f
        const val H = 442f
    }
}
