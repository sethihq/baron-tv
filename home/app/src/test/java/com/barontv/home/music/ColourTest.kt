package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Test

/** OKLCH as Chrome drew the prototype's colours (values read off its screenshots). */
class ColourTest {

    @Test
    fun matchesChromesColours() {
        // The screensaver's first colour, oklch(72% 0.19 25): outside sRGB, clipped channel by channel.
        assertEquals(Colour.rgb(255, 106, 101), Colour.oklchToRgb(0.72, 0.19, 25.0))
        // The bot's white, oklch(96% 0 none).
        assertEquals(Colour.rgb(242, 242, 242), Colour.oklchToRgb(0.96, 0.0, 0.0))
        assertEquals(Colour.rgb(255, 255, 255), Colour.oklchToRgb(1.0, 0.0, 0.0))
        assertEquals(Colour.rgb(0, 0, 0), Colour.oklchToRgb(0.0, 0.0, 0.0))
    }

    @Test
    fun roundTripsThroughOklch() {
        val oklch = DoubleArray(3)
        for (rgb in intArrayOf(Colour.rgb(229, 69, 51), Colour.rgb(30, 90, 220), Colour.rgb(80, 200, 120))) {
            Colour.rgbToOklch(rgb, oklch)
            assertEquals(rgb, Colour.oklchToRgb(oklch[0], oklch[1], oklch[2]))
        }
    }

    @Test
    fun saturateIsCssSaturate() {
        // Greys don't move; colours spread from their grey.
        assertEquals(Colour.rgb(100, 100, 100), Colour.saturate(Colour.rgb(100, 100, 100), 1.15))
        val s = Colour.saturate(Colour.rgb(200, 100, 50), 1.15)
        assertEquals(212, Colour.red(s))
        assertEquals(97, Colour.green(s))
        assertEquals(40, Colour.blue(s))
    }

    @Test
    fun vividnessIsThePrototypesScore() {
        assertEquals(1.0, Colour.vividness(Colour.rgb(255, 0, 0)), 1e-9)
        assertEquals(0.0, Colour.vividness(Colour.rgb(128, 128, 128)), 1e-9)
        // Dark red: fully saturated, but value 0.4 counts as 0.56.
        assertEquals(0.4 * 1.4, Colour.vividness(Colour.rgb(102, 0, 0)), 1e-9)
    }
}
