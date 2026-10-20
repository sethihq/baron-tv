package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Music mode's timing: the prototype's fades (edge.css), the steady tempo when nothing can be heard. */
class MusicEngineTest {

    private fun frames(e: MusicEngine, from: Double, to: Double) {
        var t = from
        while (t <= to) {
            e.frame(t)
            t += 1000.0 / 60
        }
    }

    @Test
    fun itFadesInAsThePrototypeDid() {
        val e = MusicEngine(Random(1))
        e.enter(1000.0)
        assertTrue(e.on)
        // The bot waits half a second; the light is in after 0.9 s, the black after 1.8 s, the bot at 1.7 s.
        e.frame(1400.0)
        assertEquals(0f, e.face.value, 0f)
        assertTrue(e.glow.value > 0.5f)
        e.frame(1900.0)
        assertEquals(1f, e.glow.value, 0f)
        assertTrue(e.dim.value < 1f)
        e.frame(2800.0)
        assertEquals(1f, e.dim.value, 0f)
        assertEquals(1f, e.face.value, 0f)
        assertFalse(e.lightUnderDim)
    }

    @Test
    fun itFadesOutWithTheLightUnderTheBlack() {
        val e = MusicEngine(Random(1))
        e.enter(1000.0)
        frames(e, 1000.0, 4000.0)
        e.exit(4000.0)
        assertFalse(e.on)
        assertTrue(e.lightUnderDim)
        assertFalse(e.bot.showing)
        e.frame(4900.0)
        assertEquals(0f, e.glow.value, 0f)
        assertEquals(0f, e.face.value, 0f)
        assertTrue(e.dim.value > 0f)
        assertFalse(e.settled)
        e.frame(5400.0)
        assertTrue(e.settled)
    }

    @Test
    fun theDimFollowsThePrototypesEaseOut() {
        val e = MusicEngine(Random(1))
        e.enter(0.0)
        // cubic-bezier(0.23, 1, 0.32, 1) is most of the way there by a third of the time.
        e.frame(600.0)
        assertEquals(CubicBezier.EASE_OUT.at(600.0 / 1800).toFloat(), e.dim.value, 1e-6f)
        assertTrue(e.dim.value > 0.85f)
    }

    @Test
    fun withNothingToHearASteady112BpmKeepsTime() {
        val e = MusicEngine(Random(1))
        e.hearing = false
        e.enter(1000.0)
        val swells = mutableListOf<Int>()
        var t = 1000.0
        var lastSwells = 0
        val kicks = mutableListOf<Double>()
        var lastKick = e.bot.lastKick
        while (t < 11_000) {
            e.frame(t)
            if (e.bot.lastKick != lastKick) {
                kicks += e.bot.lastKick
                lastKick = e.bot.lastKick
            }
            swells += e.light.swells - lastSwells
            lastSwells = e.light.swells
            t += 1000.0 / 60
        }
        // A kick every other beat at 112 BPM: every 1071 ms, give or take a frame.
        for (i in 1 until kicks.size) assertEquals(60000.0 / 112 * 2, kicks[i] - kicks[i - 1], 17.0)
        assertEquals(10_000.0 / (60000.0 / 112 * 2), kicks.size.toDouble(), 1.0)
        assertEquals(0.15f, e.light.high, 0f)
    }

    @Test
    fun aHeardKickSwellsTheLightFromTheBottomAndMovesTheBot() {
        val e = MusicEngine(Random(1))
        e.hearing = true
        e.enter(1000.0)
        e.kick(1500.0, 0.8)
        assertEquals(1, e.light.swells)
        assertEquals(1500.0, e.bot.lastKick, 0.0)
        e.hat(1700.0)
        assertEquals(2, e.light.swells)
    }

    @Test
    fun theFirstHearingSnapsToTheSongsLevel() {
        val e = MusicEngine(Random(1))
        e.levels(0.9, 0.4, 0.016, snap = true)
        assertEquals(0.9f, e.light.bass, 1e-6f)
        assertEquals(0.4f, e.light.high, 1e-6f)
        assertEquals(0.9, e.bot.bassSlow, 1e-9)
        // Then it eases, 0.2 a frame at 60 fps.
        e.levels(0.4, 0.4, 1.0 / 60, snap = false)
        assertEquals(0.9f - 0.5f * 0.2f, e.light.bass, 1e-4f)
    }

    @Test
    fun stillWithTheTvsAnimationsOff() {
        val e = MusicEngine(Random(1))
        e.still = true
        e.enter(1000.0)
        assertEquals(1f, e.dim.value, 0f)
        assertEquals(1f, e.glow.value, 0f)
        assertEquals(1f, e.face.value, 0f)
        assertEquals(1f, e.light.fullK, 0f)
        e.exit(2000.0)
        assertTrue(e.settled)
    }

    @Test
    fun itComesInFromTheCoversColoursAwake() {
        val e = MusicEngine(Random(1))
        e.light.setHue(200.0)
        e.enter(50_000.0)
        assertEquals(200.0, e.light.colour(0)[2], 0.0)
        assertEquals(0f, e.light.fullK, 0f)
        assertEquals(50_000.0, e.bot.lastKick, 0.0)
    }
}
