package com.barontv.home.side

import com.barontv.home.R
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {

    @Test
    fun aStepIsFivePercentOfTheRangeAtLeastOne() {
        assertEquals(5, Volume.step(100))
        assertEquals(1, Volume.step(15))
        assertEquals(1, Volume.step(10))
        assertEquals(2, Volume.step(30))
    }

    @Test
    fun upAndDownMoveByAStepWithinTheRange() {
        assertEquals(40, Volume.next(35, 100, up = true))
        assertEquals(30, Volume.next(35, 100, up = false))
        assertEquals(100, Volume.next(98, 100, up = true))
        assertEquals(0, Volume.next(3, 100, up = false))
        assertEquals(0, Volume.next(0, 100, up = false))
        assertEquals(8, Volume.next(7, 15, up = true))
    }

    @Test
    fun theNumberIsAPercentOfTheRange() {
        assertEquals(35, Volume.percent(35, 100))
        assertEquals(47, Volume.percent(7, 15))
        assertEquals(100, Volume.percent(15, 15))
        assertEquals(0, Volume.percent(0, 15))
        assertEquals(0, Volume.percent(5, 0))
    }

    @Test
    fun aPointerSetsTheLevelInFives() {
        assertEquals(35, Volume.indexFor(36f, 100))
        assertEquals(40, Volume.indexFor(38f, 100))
        assertEquals(0, Volume.indexFor(1f, 100))
        assertEquals(100, Volume.indexFor(100f, 100))
        assertEquals(5, Volume.indexFor(35f, 15))
    }

    @Test
    fun theGlyphMutesAtZero() {
        assertEquals(R.drawable.ph_speaker_simple_x, Volume.glyph(0))
        assertEquals(R.drawable.ph_speaker_simple_low, Volume.glyph(5))
        assertEquals(R.drawable.ph_speaker_simple_low, Volume.glyph(45))
        assertEquals(R.drawable.ph_speaker_simple_high, Volume.glyph(50))
        assertEquals(R.drawable.ph_speaker_simple_high, Volume.glyph(100))
    }
}
