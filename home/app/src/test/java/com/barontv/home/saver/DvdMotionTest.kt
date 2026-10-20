package com.barontv.home.saver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The DVD logo's flight: speed, bounces, a new colour at each, the corner pulse, the jumps. */
class DvdMotionTest {
    private val maxX = DvdMotion.SCREEN_W - DvdMotion.LOGO_W
    private val maxY = DvdMotion.SCREEN_H - DvdMotion.LOGO_H

    private fun motion() = DvdMotion(Random(2)).apply { start(0.0) }

    @Test
    fun itGlides105DpASecondEachWay() {
        val m = motion()
        m.x = 100.0
        m.y = 100.0
        m.vx = DvdMotion.SPEED
        m.vy = -DvdMotion.SPEED
        m.step(40.0)
        assertEquals(100 + 105 * 0.04, m.x, 1e-9)
        assertEquals(100 - 105 * 0.04, m.y, 1e-9)
        // A stalled frame moves at most 50 ms worth, as the prototype's did.
        m.step(1040.0)
        assertEquals(100 + 105 * 0.09, m.x, 1e-9)
    }

    @Test
    fun itBouncesOffAnEdgeInANewColour() {
        val m = motion()
        m.x = maxX - 1
        m.y = 200.0
        m.vx = DvdMotion.SPEED
        m.vy = DvdMotion.SPEED
        val before = m.colour
        assertEquals(DvdMotion.EDGE, m.step(16.0 + 0.0))
        assertEquals(maxX, m.x, 0.0)
        assertEquals(-DvdMotion.SPEED, m.vx, 0.0)
        assertEquals(DvdMotion.SPEED, m.vy, 0.0)
        assertNotEquals(before, m.colour)
        // And off the top.
        m.y = 0.5
        m.vy = -DvdMotion.SPEED
        assertEquals(DvdMotion.EDGE, m.step(32.0))
        assertEquals(0.0, m.y, 0.0)
        assertEquals(DvdMotion.SPEED, m.vy, 0.0)
    }

    @Test
    fun theCornerEveryoneWaitsFor() {
        val m = motion()
        m.x = maxX - 0.5
        m.y = maxY - 0.5
        m.vx = DvdMotion.SPEED
        m.vy = DvdMotion.SPEED
        assertEquals(DvdMotion.CORNER, m.step(16.0))
        assertEquals(16.0, m.cornerAt, 0.0)
        assertEquals(-DvdMotion.SPEED, m.vx, 0.0)
        assertEquals(-DvdMotion.SPEED, m.vy, 0.0)
    }

    @Test
    fun thePulseSwellsTo118AndBackIn420Ms() {
        val m = motion()
        m.x = 0.5
        m.y = 0.5
        m.vx = -DvdMotion.SPEED
        m.vy = -DvdMotion.SPEED
        m.step(16.0)
        assertEquals(1f, m.scale(16.0), 0f)
        var peak = 1f
        var t = 16.0
        while (t < 16.0 + 420) {
            peak = maxOf(peak, m.scale(t))
            t += 1.0
        }
        assertEquals(1.18f, peak, 0.002f)
        assertEquals(1f, m.scale(16.0 + 420), 0f)
        // On the prototype's ease-out the peak comes early (by 60 ms), then it settles slowly.
        assertTrue(m.scale(16.0 + 60) > 1.15f)
        assertTrue(m.scale(16.0 + 300) < 1.02f)
    }

    @Test
    fun neverTheSameColourTwiceInARow() {
        val m = motion()
        val seen = HashSet<Int>()
        repeat(2000) {
            val before = m.colour
            m.nextColour()
            assertNotEquals(before, m.colour)
            seen += m.colour
        }
        assertEquals(DvdMotion.COLOURS.size, seen.size)
    }

    @Test
    fun itStaysOnScreenForTenMinutes() {
        val m = motion()
        var t = 0.0
        var bounces = 0
        var corners = 0
        while (t < 600_000) {
            t += 1000.0 / 60
            when (m.step(t)) {
                DvdMotion.EDGE -> bounces++
                DvdMotion.CORNER -> corners++
            }
            assertTrue(m.x in 0.0..maxX)
            assertTrue(m.y in 0.0..maxY)
        }
        // 105 dp a second across 810 and 462 dp: a side every 7.7 s and the top or bottom every 4.4 s,
        // about 214 in ten minutes.
        assertTrue("bounces $bounces", bounces + corners in 195..230)
    }

    @Test
    fun withMotionOffItJumpsSomewhereOnScreen() {
        val m = motion()
        repeat(100) {
            val before = m.colour
            m.jump()
            assertTrue(m.x in 0.0..maxX)
            assertTrue(m.y in 0.0..maxY)
            assertNotEquals(before, m.colour)
        }
        assertEquals(10_000L, DvdMotion.JUMP_MS)
    }

    @Test
    fun theColoursAreThePrototypes() {
        // oklch(72% 0.19 h) for 25, 60, 95, 145, 195, 250, 300, 340; the first as Chrome drew it.
        assertEquals(8, DvdMotion.COLOURS.size)
        assertEquals(0xFFFF6A65.toInt(), DvdMotion.COLOURS[0])
    }
}
