package com.barontv.remote.ui.playing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.barontv.remote.R
import com.barontv.remote.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The small sums behind the Playing tab: colours, times, the slider's drag, each app's look. */
class PlayingMathsTest {
    /** Within one step per channel: the theme's colours were rounded to whole sRGB steps. */
    private fun assertNear(expected: Long, actual: Color) {
        val e = expected.toInt()
        val a = actual.toArgb()
        for (shift in listOf(24, 16, 8, 0)) {
            val x = (e shr shift) and 0xFF
            val y = (a shr shift) and 0xFF
            assertTrue("expected #%08X, got #%08X".format(e, a), abs(x - y) <= 1)
        }
    }

    @Test
    fun `OKLCH comes out as the theme's colours, which were checked against Chrome`() {
        assertNear(0xFF2DCB65, oklch(0.74f, 0.19f, 150f))
        assertNear(0xFF4BB0F8, oklch(0.73f, 0.14f, 243f))
        assertNear(0xFFEEEEEE, oklch(0.95f, 0f, 0f))
        assertNear(0xFFA1A1A1, oklch(0.71f, 0f, 0f))
        assertNear(0xFF1A1A1C, oklch(0.218f, 0.004f, 286f))
    }

    @Test
    fun `and back again`() {
        val (l, c, h) = Palette.Spotify.toOklch()
        assertEquals(0.74f, l, 0.005f)
        assertEquals(0.19f, c, 0.005f)
        assertEquals(150f, h, 1f)
    }

    @Test
    fun `the glow takes the cover's hue at one lightness, its chroma capped`() {
        val glow = glowFor(Color(0xFF1ED760))
        // The same as phone.js gives: oklch(54% 0.16 148.7 / 0.4), then 44% at 0.9 of the chroma, 0.15.
        assertNear(0x66008633, glow.inner)
        assertEquals(0.4f, glow.inner.alpha, 0.01f)
        assertEquals(0.15f, glow.outer.alpha, 0.01f)
        assertEquals(0.44f, glow.outer.copy(alpha = 1f).toOklch().l, 0.02f)
    }

    @Test
    fun `yellow glows amber, and a grey cover glows grey`() {
        assertNear(0x66A95600, glowFor(Color(0xFFFFC80B)).inner)
        val grey = glowFor(Color(0xFF808080)).inner
        assertEquals(grey.red, grey.green, 0.002f)
        assertEquals(grey.green, grey.blue, 0.002f)
    }

    @Test
    fun `mixing starts at one colour and ends at the other`() {
        val a = Color(0xFF2DCB65)
        val b = Color(0x264BB0F8)
        assertNear(a.toArgb().toLong() and 0xFFFFFFFF, mixOklab(a, b, 0f))
        assertNear(b.toArgb().toLong() and 0xFFFFFFFF, mixOklab(a, b, 1f))
        assertEquals(0f, mixOklab(a, b.copy(alpha = 0f), 1f).alpha, 0f)
    }

    @Test
    fun `times read m ss, and h mm ss past the hour`() {
        assertEquals("0:00", clock(0))
        assertEquals("1:03", clock(63))
        assertEquals("10:00", clock(600))
        assertEquals("1:02:05", clock(3725))
        assertEquals("0:00", clock(-5))
    }

    @Test
    fun `a drag moves the level by its share of the track, from where it started`() {
        assertEquals(65, dragLevel(start = 40, travelPx = 63f, trackPx = 252f))
        assertEquals(40, dragLevel(start = 40, travelPx = 0f, trackPx = 252f))
        assertEquals(0, dragLevel(start = 40, travelPx = -1000f, trackPx = 252f))
        assertEquals(100, dragLevel(start = 95, travelPx = 100f, trackPx = 252f))
        assertEquals(40, dragLevel(start = 40, travelPx = 10f, trackPx = 0f))
    }

    @Test
    fun `a tick at every tenth`() {
        assertTrue(crossedTenth(39, 40))
        assertTrue(crossedTenth(50, 49))
        assertFalse(crossedTenth(40, 49))
    }

    @Test
    fun `Spotify shows its cover, other apps a calm tile in their own colour`() {
        assertEquals(R.drawable.playing_cover_spotify, lookFor(SPOTIFY).cover)
        val youtube = lookFor("com.google.android.youtube.tv")
        assertNull(youtube.cover)
        val (l, c, h) = youtube.tile.toOklch()
        assertEquals(0.32f, l, 0.01f)
        assertTrue(c <= 0.076f)
        assertEquals(29f, h, 3f)
    }

    @Test
    fun `an app the TV wasn't told about keeps one colour`() {
        assertEquals(lookFor("org.example.radio").tile, lookFor("org.example.radio").tile)
    }
}
