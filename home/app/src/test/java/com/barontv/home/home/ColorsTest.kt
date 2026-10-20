package com.barontv.home.home

import com.barontv.home.core.Tokens
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorsTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun oklchMatchesTheTokens() {
        // Tokens.kt was converted from the prototype's OKLCH by hand; the converter must agree.
        assertEquals(hex(Tokens.CALENDAR_RED), hex(Oklab.oklch(0.68, 0.2, 25.0)))
        assertEquals(hex(Tokens.TILE), hex(Oklab.oklch(0.20, 0.0, 0.0)))
        assertEquals(hex(Tokens.TEXT), hex(Oklab.oklch(0.94, 0.0, 0.0)))
        assertEquals(hex(Tokens.TEXT_2), hex(Oklab.oklch(0.71, 0.0, 0.0)))
        assertEquals(hex(Tokens.FILL), hex(Oklab.oklch(0.218, 0.004, 286.0)))
        assertEquals(hex(Tokens.POSTER_FOCUS), hex(Oklab.oklch(0.29, 0.004, 286.0)))
        assertEquals(hex(Tokens.DOCK_FRAME), hex(Oklab.oklch(0.08, 0.0, 0.0)))
    }

    @Test
    fun outOfGamutColoursClipLikeChrome() {
        // The sun square's lower stop is a touch outside sRGB (red 1.005): it clips to full red.
        assertEquals("#FF9C3B", hex(Oklab.oklch(0.78, 0.16, 60.0)))
    }

    @Test
    fun lchRoundTrips() {
        val lch = DoubleArray(3)
        Oklab.lchFromRgb(0xFC, 0x58, 0x55, lch)
        assertEquals("#FC5855", hex(Oklab.oklch(lch[0], lch[1], lch[2])))
    }

    @Test
    fun glowColorsMatchThePrototype() {
        // The prototype's setGlow() for YouTube's vivid red, read from the page:
        // core oklch(50% 0.180 28.9 / 0.6), mid oklch(40% 0.162 28.9 / 0.26), far oklch(46% 0.135 3.9 / 0.16).
        val g = GlowColors.forArt(rgb(255, 12, 12))
        assertGlow(doubleArrayOf(0.50, 0.180, 28.9, 0.6), g.core)
        assertGlow(doubleArrayOf(0.40, 0.162, 28.9, 0.26), g.mid)
        assertGlow(doubleArrayOf(0.46, 0.135, 3.9, 0.16), g.far)
        // Prime Video's blue: chroma under the cap; oklch(50% 0.175 252.8 / 0.6).
        assertGlow(doubleArrayOf(0.50, 0.175, 252.8, 0.6), GlowColors.forArt(rgb(2, 99, 182)).core)
        // The App Store's amber sits at hue 74.2, just short of the yellows that lean to amber.
        assertGlow(doubleArrayOf(0.50, 0.167, 74.2, 0.6), GlowColors.forArt(rgb(206, 138, 0)).core)
    }

    @Test
    fun yellowsLeanToAmberAndGreysStayGrey() {
        assertEquals(68.0, GlowColors.forArt(rgb(200, 190, 20)).core[2], 1e-9)
        val grey = GlowColors.forArt(VividColor.FALLBACK)
        assertEquals(0.0, grey.core[1], 1e-9)
        assertEquals(0.0, grey.far[1], 1e-9)
    }

    private fun assertGlow(expected: DoubleArray, actual: DoubleArray) {
        assertEquals("L", expected[0], actual[0], 1e-9)
        assertEquals("C", expected[1], actual[1], 0.0006)
        assertEquals("h", expected[2], actual[2], 0.06)
        assertEquals("alpha", expected[3], actual[3], 1e-9)
    }

    @Test
    fun vividPicksTheMostSaturatedBrightPixel() {
        // A white tile with a red logo (YouTube): the logo wins, made a touch more vivid.
        val pixels = IntArray(144) { rgb(245, 245, 245) }
        pixels[60] = rgb(230, 20, 20)
        pixels[61] = rgb(200, 60, 60)
        assertEquals(hex(rgb(230, 0, 0)), hex(VividColor.pick(pixels)))
    }

    @Test
    fun vividLiftsANearlyGreyPicture() {
        // Nothing saturated enough (score under 0.25): the average, lifted to at least 55% value.
        val pixels = IntArray(144) { rgb(68, 68, 68) }
        assertEquals(hex(rgb(140, 140, 140)), hex(VividColor.pick(pixels)))
        // A dark but saturated red scores low (value counts), so the average decides.
        val dark = IntArray(144) { rgb(40, 0, 0) }
        assertEquals(hex(rgb(140, 0, 0)), hex(VividColor.pick(dark)))
    }

    @Test
    fun vividKeepsHueAndRaisesValue() {
        // A mid blue (hue 210, saturation 0.857, value 0.549): saturation x1.1, value kept.
        val pixels = IntArray(144) { rgb(20, 80, 140) }
        assertEquals(hex(rgb(8, 74, 140)), hex(VividColor.pick(pixels)))
    }

    @Test
    fun emptyArtIsGrey() {
        assertEquals(VividColor.FALLBACK, VividColor.pick(IntArray(0)))
    }

    @Test
    fun boxDownAverages() {
        val side = 4
        val pixels = IntArray(side * side) { if (it % 2 == 0) rgb(255, 0, 0) else rgb(0, 0, 255) }
        val out = VividColor.boxDown(pixels, side, 2)
        assertEquals(4, out.size)
        assertEquals(hex(rgb(127, 0, 127)), hex(out[0]))
    }

    @Test
    fun mixColorIsLinearInSrgb() {
        assertEquals(hex(Tokens.FILL), hex(mixColor(Tokens.FILL, Tokens.POSTER_FOCUS, 0f)))
        assertEquals(hex(Tokens.POSTER_FOCUS), hex(mixColor(Tokens.FILL, Tokens.POSTER_FOCUS, 1f)))
        assertEquals("#808080", hex(mixColor(rgb(0, 0, 0), rgb(255, 255, 255), 0.5f)))
    }
}
