package com.barontv.home.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IslandMathTest {

    @Test
    fun timesReadAsThePrototypes() {
        assertEquals("0:00", IslandMath.mmss(0))
        assertEquals("0:07", IslandMath.mmss(7_000))
        assertEquals("0:07", IslandMath.mmss(7_400))
        // Rounded, as Math.round: half a second goes up.
        assertEquals("0:08", IslandMath.mmss(7_500))
        assertEquals("3:13", IslandMath.mmss(193_000))
        assertEquals("12:05", IslandMath.mmss(725_000))
        assertEquals("0:00", IslandMath.mmss(-3_000))
    }

    @Test
    fun timeLeftHasTheLeadingHyphen() {
        assertEquals("-3:13", IslandMath.left(7_000, 200_000))
        assertEquals("-0:00", IslandMath.left(210_000, 200_000))
    }

    @Test
    fun positionMovesOnWhilePlayingAtItsSpeed() {
        assertEquals(17_000L, IslandMath.position(7_000, 1_000, 1f, true, 200_000, 11_000))
        assertEquals(27_000L, IslandMath.position(7_000, 1_000, 2f, true, 200_000, 11_000))
        // Paused, or with no time of report, it stays put.
        assertEquals(7_000L, IslandMath.position(7_000, 1_000, 1f, false, 200_000, 11_000))
        assertEquals(7_000L, IslandMath.position(7_000, 0, 1f, true, 200_000, 11_000))
        // Never past the end, never below the start.
        assertEquals(200_000L, IslandMath.position(199_000, 1_000, 1f, true, 200_000, 11_000))
        assertEquals(0L, IslandMath.position(-500, 1_000, 1f, false, 200_000, 11_000))
    }

    @Test
    fun progressIsAFractionOfTheSong() {
        assertEquals(0.035f, IslandMath.progress(7_000, 200_000), 1e-6f)
        assertEquals(0f, IslandMath.progress(7_000, 0), 0f)
        assertEquals(1f, IslandMath.progress(300_000, 200_000), 0f)
    }

    @Test
    fun theTimesRedrawWhenEitherShownSecondTurns() {
        // At 7.2 s the elapsed time turns at 7.5 s: 300 ms (and a millisecond to be past it).
        assertEquals(301L, IslandMath.msToNextChange(7_200, 200_000, 1f))
        // With a 203.25 s song the time left turns at 6.75 s and every second from there.
        assertEquals(51L, IslandMath.msToNextChange(6_700, 203_250, 1f))
        // Twice the speed, half the wait.
        assertEquals(151L, IslandMath.msToNextChange(7_200, 200_000, 2f))
        assertEquals(1000L, IslandMath.msToNextChange(7_200, 200_000, 0f))
    }

    @Test
    fun barsRunThereAndBackFromTheirOwnOffsets() {
        // Bar one: 820 ms each way from its shortest at 0.
        assertEquals(0f, IslandMath.barPhase(0, 0), 1e-6f)
        assertEquals(0.5f, IslandMath.barPhase(0, 410), 1e-6f)
        assertEquals(1f, IslandMath.barPhase(0, 820), 1e-6f)
        assertEquals(0.5f, IslandMath.barPhase(0, 1230), 1e-6f)
        // Bar two starts 320 ms into its 640: half way up at time 0.
        assertEquals(0.5f, IslandMath.barPhase(1, 0), 1e-6f)
        assertEquals(0.28f, IslandMath.barScale(0f), 1e-6f)
        assertEquals(1f, IslandMath.barScale(1f), 1e-6f)
    }

    @Test
    fun vividColourPicksTheMostSaturatedPixel() {
        val grey = 0xFF808080.toInt()
        val red = 0xFFE03030.toInt()
        val pixels = IntArray(144) { if (it == 77) red else grey }
        val v = IslandMath.vivid(pixels)
        val hsv = FloatArray(3)
        IslandMath.toHsv(v, hsv)
        assertEquals(0f, hsv[0], 1f)
        assertEquals(0.866f, hsv[1], 0.02f)
    }

    @Test
    fun aGreyCoverGivesALightGreyAccent() {
        val pixels = IntArray(144) { 0xFF606060.toInt() }
        assertEquals(0xFFE4E4E4.toInt(), IslandMath.accent(IslandMath.vivid(pixels)))
    }

    @Test
    fun theAccentMatchesThePrototypeForItsDrawnCover() {
        // The prototype's drawn cover for its first song (hsl 350 72% 58% to hsl 30 70% 24%) gives
        // --isl-accent oklch(80% 0.170 17.8) in Chrome.
        val lch = IslandMath.accentLch(IslandMath.vivid(drawnCover()))
        assertEquals(0.80f, lch[0], 1e-6f)
        assertEquals(0.17f, lch[1], 1e-6f)
        assertEquals(17.8f, lch[2], 2f)
    }

    @Test
    fun oklchRoundTrips() {
        val lch = FloatArray(3)
        for (c in intArrayOf(0xFF4BB0F8.toInt(), 0xFF2EB45C.toInt(), 0xFFEBEBEB.toInt(), 0xFF1A1A1C.toInt())) {
            IslandMath.toOklch(c, lch)
            val back = IslandMath.fromOklch(lch[0], lch[1], lch[2])
            for (shift in intArrayOf(16, 8, 0)) {
                val off = abs((c shr shift and 0xFF) - (back shr shift and 0xFF))
                assertTrue("channel $shift off by $off", off <= 1)
            }
        }
    }

    /** The prototype's drawnCover(0), a 12 x 12 sample of its diagonal gradient. */
    private fun drawnCover(): IntArray {
        val a = hsl(350f, 0.72f, 0.58f)
        val b = hsl(30f, 0.70f, 0.24f)
        return IntArray(144) { i ->
            val x = (i % 12 + 0.5f) / 12f
            val y = (i / 12 + 0.5f) / 12f
            val t = ((x + y) / 2f).coerceIn(0f, 1f)
            val r = ((a[0] + (b[0] - a[0]) * t) * 255).toInt()
            val g = ((a[1] + (b[1] - a[1]) * t) * 255).toInt()
            val bl = ((a[2] + (b[2] - a[2]) * t) * 255).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    private fun hsl(h: Float, s: Float, l: Float): FloatArray {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m)
    }
}
