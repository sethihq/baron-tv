package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The edge light's maths: its colours from the cover, where its dabs sit, how they swell and ease. */
class EdgeLightTest {

    @Test
    fun threeNeighbouringHuesFromTheCover() {
        val light = EdgeLight()
        val oklch = DoubleArray(3)
        val blue = Colour.rgb(30, 90, 220)
        Colour.rgbToOklch(blue, oklch)
        light.setCover(blue)
        // The colour itself, 55 degrees back and 45 forward, at the prototype's lightness and chroma.
        assertHue(oklch[2], light.goal(0)[2])
        assertHue(oklch[2] - 55, light.goal(1)[2])
        assertHue(oklch[2] + 45, light.goal(2)[2])
        assertEquals(62.0, light.goal(0)[0], 0.0)
        assertEquals(0.2, light.goal(0)[1], 1e-9)
        assertEquals(56.0, light.goal(1)[0], 0.0)
        assertEquals(0.22, light.goal(1)[1], 1e-9)
        assertEquals(70.0, light.goal(2)[0], 0.0)
        assertEquals(0.16, light.goal(2)[1], 1e-9)
    }

    @Test
    fun oliveLeansToAmber() {
        // A mustard cover: its hue lands in olive (75 to 120), so it becomes amber, 68.
        val light = EdgeLight()
        val mustard = Colour.rgb(150, 140, 30)
        val oklch = DoubleArray(3)
        Colour.rgbToOklch(mustard, oklch)
        assertTrue("hue ${oklch[2]}", oklch[2] > 75 && oklch[2] < 120)
        light.setCover(mustard)
        assertEquals(68.0, light.goal(0)[2], 0.0)
        // And any of the three that falls there: 40 + 45 = 85 is olive too; 40 - 55 wraps to 345.
        light.setHue(40.0)
        assertEquals(68.0, light.goal(2)[2], 0.0)
        assertEquals(345.0, light.goal(1)[2], 1e-9)
    }

    @Test
    fun aGreyCoverGivesASoftSilver() {
        val light = EdgeLight()
        light.setCover(Colour.rgb(128, 128, 130))
        assertEquals(0.2 * 0.15, light.goal(0)[1], 1e-9)
        assertEquals(0.22 * 0.15, light.goal(1)[1], 1e-9)
    }

    @Test
    fun coloursEaseToANewCoverTheShortWayRound() {
        val light = EdgeLight()
        light.setHue(10.0)
        light.snapColours()
        light.setHue(330.0)
        // 0.04 a frame at 60 fps: after one frame, 4% of the way, back through 0 rather than forwards.
        light.frame(1000.0, 1.0 / 60)
        assertEquals(10.0 - 40 * 0.04, light.colour(0)[2], 1e-6)
        repeat(600) { light.frame(1000.0 + it * 16.7, 1.0 / 60) }
        assertEquals(330.0, light.colour(0)[2], 0.0)
    }

    @Test
    fun theRimRunsClockwiseFromTheTopCentre() {
        val light = EdgeLight()
        val n = light.dabs
        // Dab 0 at the top centre, a quarter round on the right edge, halfway at the bottom centre, each 3
        // pixels outside the 480 x 270 canvas.
        assertEquals(240f, light.x[0], 0.01f)
        assertEquals(-3f, light.y[0], 0.01f)
        assertEquals(483f, light.x[n / 4], 0.01f)
        assertEquals(240f, light.x[n / 2], 0.01f)
        assertEquals(273f, light.y[n / 2], 0.01f)
        // None in from the edge: the round corners' dabs sit at most a pixel inside.
        for (i in 0 until n) {
            val inset = min(min(light.x[i], 480f - light.x[i]), min(light.y[i], 270f - light.y[i]))
            assertTrue("dab $i at ${light.x[i]}, ${light.y[i]}", inset <= 1f)
        }
    }

    @Test
    fun theTideAndTheCornersShapeTheLight() {
        val light = EdgeLight()
        light.bass = 0.3f
        light.frame(10_000.0, 0.0)
        val time = 10.0
        val breath = 1 + 0.05 * sin(time * 2 * PI / 5.5)
        for (i in 0 until light.dabs) {
            val t = i.toDouble() / light.dabs
            val tide = 0.5 * sin(2 * PI * 3 * t + time * 0.5) + 0.3 * sin(2 * PI * 5 * t - time * 0.75 + 1.3) +
                0.2 * sin(2 * PI * 8 * t + time * 1.2 + 0.4)
            val straight = 14 * (1 + tide * 0.45) * 1.15 * breath
            // On the straight edges the radius is the tide's; in the round corners 1.3 times it.
            val r = light.radius[i].toDouble()
            assertTrue("dab $i: $r", abs(r - straight) < 1e-3 || abs(r - straight * 1.3) < 1e-3)
        }
    }

    @Test
    fun aKickSwellsFromTheBottomBothWays() {
        val light = EdgeLight()
        light.full = 1f
        light.fullK = 1f
        light.swell(0.5f, 10_000.0, 1f)
        light.frame(11_000.0, 0.0)
        val withSwell = light.radius.copyOf()
        light.clearSwells()
        light.frame(11_000.0, 0.0)
        val without = light.radius
        // A second on, its fronts have run a third of the way round each way from the bottom centre.
        val n = light.dabs
        val ahead = (0.8 * n).roundToInt()
        val behind = (0.2 * n).roundToInt()
        assertTrue(withSwell[ahead] - without[ahead] > 3f)
        assertTrue(withSwell[behind] - without[behind] > 3f)
        // Where they started and far from them, nothing.
        assertEquals(without[n / 2], withSwell[n / 2], 0.001f)
        assertEquals(without[0], withSwell[0], 0.001f)
    }

    @Test
    fun swellsFadeWithin2600Ms() {
        val light = EdgeLight()
        light.swell(0.5f, 1000.0, 1f)
        light.frame(3599.0, 0.0)
        assertEquals(1, light.swells)
        light.frame(3600.0, 0.0)
        assertEquals(0, light.swells)
    }

    @Test
    fun atMostTenSwells() {
        val light = EdgeLight()
        repeat(15) { light.swell(0.5f, 1000.0 + it, 1f) }
        assertEquals(10, light.swells)
    }

    @Test
    fun theLightGrowsToFullOnTheClockNotTheFrameRate() {
        val light = EdgeLight()
        light.full = 1f
        // 0.03 a frame at 60 fps.
        light.frame(1000.0, 1.0 / 60)
        assertEquals(0.03f, light.fullK, 1e-4f)
        repeat(119) { light.frame(1000.0, 1.0 / 60) }
        assertEquals(1 - Math.pow(0.97, 120.0), light.fullK.toDouble(), 1e-3)
        // The same two seconds at 30 fps.
        val slow = EdgeLight()
        slow.full = 1f
        repeat(60) { slow.frame(1000.0, 1.0 / 30) }
        assertEquals(light.fullK, slow.fullK, 1e-3f)
    }

    @Test
    fun eachDabCarriesItsShareOfThePrototypesLight() {
        val light = EdgeLight()
        light.full = 1f
        light.fullK = 1f
        light.bass = 0.3f
        light.high = 0f
        light.frame(10_000.0, 0.0)
        // At full, with no swell and no sparkle, the prototype's alpha is 0.085 x 1.6 x breath; with 160
        // dabs instead of 220 each carries 220/160 of it.
        val breath = 1 + 0.2 * sin(10.0 * 2 * PI / 5.5)
        val alpha = 0.085 * 1.6 * breath * 220 / 160 * 255
        for (i in 0 until light.dabs) assertEquals(alpha, (light.argb[i] ushr 24).toDouble(), 0.6)
    }

    private fun assertHue(expected: Double, actual: Double) {
        val d = abs(((expected - actual) % 360 + 540) % 360 - 180)
        assertTrue("hue $actual, expected $expected", d < 1e-6)
    }
}
