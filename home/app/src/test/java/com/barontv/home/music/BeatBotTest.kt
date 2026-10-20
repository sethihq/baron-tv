package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The beat bot's timing: springs that settle, blinks on the prototype's schedule, its moods and reactions. */
class BeatBotTest {

    private fun bot(seed: Int = 1) = BeatBot(Random(seed)).apply { showing = true }

    // Frames at 60 fps from [from] to [to], kicking every [beatMs] if given.
    private fun BeatBot.run(from: Double, to: Double, beatMs: Double = 0.0, amp: Double = 0.9, bass: Double = 0.3) {
        var t = from
        var next = from
        while (t <= to) {
            if (beatMs > 0 && t >= next) {
                kick(t, amp)
                next += beatMs
            }
            update(t, bass)
            t += 1000.0 / 60
        }
    }

    @Test
    fun everyDanceSettlesOnItsSprings() {
        for (move in BeatBot.Move.values()) {
            val b = bot()
            b.move = move
            b.moveUntil = 1e15
            b.gestureNext = 1e15
            b.run(1000.0, 5000.0, beatMs = 500.0, amp = 1.0)
            // The beat stops: within two seconds everything is back at rest.
            b.run(5000.0, 7000.0)
            for ((name, v) in listOf("x" to b.x - b.xGoal, "y" to b.y, "tilt" to b.tilt - b.tiltGoal, "squash" to b.squash, "eyeL" to b.eyeL, "eyeR" to b.eyeR)) {
                assertTrue("$move $name $v", abs(v) < 0.05)
            }
            for ((name, v) in listOf("xv" to b.xv, "yv" to b.yv, "tiltV" to b.tiltV, "squashV" to b.squashV)) {
                assertTrue("$move $name $v", abs(v) < 0.5)
            }
        }
    }

    @Test
    fun aHopGoesUpAndComesBack() {
        val b = bot()
        b.move = BeatBot.Move.HOP
        b.moveUntil = 1e15
        b.moodUntil = 1e15
        b.gestureNext = 1e15
        b.lastKick = 1000.0
        b.update(1000.0, 0.3)
        b.kick(1000.0, 1.0)
        var lowest = 0.0
        var t = 1000.0
        while (t < 2000) {
            t += 1000.0 / 60
            b.update(t, 0.3)
            lowest = minOf(lowest, b.y)
        }
        // Up (negative y) by more than 15 px, then home.
        assertTrue("peak $lowest", lowest < -15)
        assertTrue(abs(b.y) < 1)
    }

    @Test
    fun blinksShutIn70MsAndOpenOver120() {
        assertEquals(0.0, BeatBot.blinkShape(0.0), 1e-9)
        assertEquals(0.5, BeatBot.blinkShape(0.035), 1e-9)
        assertEquals(1.0, BeatBot.blinkShape(0.07), 1e-9)
        assertEquals(0.5, BeatBot.blinkShape(0.13), 1e-9)
        assertEquals(0.0, BeatBot.blinkShape(0.19), 1e-9)
        assertEquals(0.0, BeatBot.blinkShape(-0.1), 1e-9)
    }

    @Test
    fun itBlinksEvery1600To4800MsAQuarterOfThemTwice() {
        val b = bot(4)
        b.lastKick = 1e15 // awake, no dancing
        var t = 1000.0
        val starts = mutableListOf<Double>()
        var doubles = 0
        var last = b.blinkAt
        while (t < 1000.0 + 600_000) {
            b.update(t, 0.3)
            if (b.blinkAt != last) {
                starts += b.blinkAt
                if (b.twice) doubles++
                last = b.blinkAt
            }
            t += 1000.0 / 60
        }
        for (i in 2 until starts.size) {
            val gap = starts[i] - starts[i - 1]
            assertTrue("gap $gap", gap >= 1600 && gap <= 4800 + 17)
        }
        val share = doubles.toDouble() / starts.size
        assertTrue("double blinks $share", share > 0.18 && share < 0.32)
    }

    @Test
    fun aDoubleBlinkShutsTwice() {
        val b = bot()
        b.lastKick = 1e15
        b.nextBlink = 1e15
        b.gesture(1000.0, BeatBot.Gesture.BLINK)
        // Its second blink starts 240 ms after the first: shut at 70 and at 310 ms, open between.
        val heights = mutableListOf<Float>()
        for (ms in listOf(70.0, 200.0, 310.0, 450.0)) {
            b.update(1000.0 + ms, 0.3)
            heights += b.pose.eyes[0].h
        }
        assertTrue(heights[0] < 10)
        assertTrue(heights[1] > 50)
        assertTrue(heights[2] < 10)
        assertTrue(heights[3] > 50)
    }

    @Test
    fun quietMakesItSleepyAndTheBeatWakesItWithAStart() {
        val b = bot()
        b.run(1000.0, 5000.0, beatMs = 500.0)
        assertTrue(b.sleepy < 0.05)
        // 3.5 s without a kick: its eyes droop.
        b.run(5000.0, 10_000.0)
        assertTrue("sleepy ${b.sleepy}", b.sleepy > 0.6)
        assertEquals(EyePose.ROUND, b.pose.eyes[0].kind)
        // The beat back: a gasp (big eyes) then wide awake.
        b.kick(10_000.0, 0.9)
        assertTrue(b.surpriseUntil > 10_000.0)
        b.run(10_000.0, 10_300.0)
        assertTrue("surprise ${b.surprise}", b.surprise > 0.9)
        b.run(10_300.0, 12_500.0, beatMs = 500.0)
        assertTrue(b.sleepy < 0.05)
    }

    @Test
    fun aDropMakesItGasp() {
        val b = bot()
        b.surpriseNext = 0.0
        b.run(1000.0, 4000.0, beatMs = 500.0, bass = 0.2)
        assertTrue(b.surpriseUntil < 1000.0)
        // The bass jumps: big eyes and an "o", the smile gone.
        b.run(4000.0, 4400.0, beatMs = 500.0, bass = 1.0)
        assertTrue(b.surprise > 0.9)
        assertTrue(b.pose.oh > 0.9)
        assertTrue(b.pose.mouthLine == 0f)
    }

    @Test
    fun happyEyesCloseToALineThenArch() {
        val b = bot()
        b.lastKick = 1e15
        b.nextBlink = 1e15
        b.joyUntil = 1e15
        // Joy eases in at 9 a second: first the eye flattens towards a 10 px line, then it bends into ^.
        b.update(1000.0, 0.3)
        b.update(1000.0 + 1000.0 / 60, 0.3)
        assertEquals(EyePose.ROUND, b.pose.eyes[0].kind)
        assertTrue(b.pose.eyes[0].h < 76f)
        b.run(1000.0 + 2000.0 / 60, 3000.0)
        val e = b.pose.eyes[0]
        assertEquals(EyePose.ARCH, e.kind)
        assertTrue(e.lift > 0.95f)
        assertTrue(e.stroke > 14f)
    }

    @Test
    fun heartsPopInWhereTheEyesWere() {
        val b = bot()
        b.lastKick = 1e15
        b.gesture(1000.0, BeatBot.Gesture.HEARTS)
        b.run(1000.0, 2500.0)
        assertEquals(EyePose.HEART, b.pose.eyes[0].kind)
        assertEquals(EyePose.HEART, b.pose.eyes[1].kind)
        assertEquals(64f, b.pose.eyes[0].size, 3f)
    }

    @Test
    fun newDancesAndMoodsComeOnTheBeatAndChange() {
        val b = bot(11)
        var moves = 0
        var moods = 0
        var lastMove = b.move
        var lastMood = b.mood
        var t = 1000.0
        while (t < 121_000) {
            b.kick(t, 0.9)
            if (b.move != lastMove) {
                moves++
                lastMove = b.move
            }
            if (b.mood != lastMood) {
                moods++
                lastMood = b.mood
            }
            t += 500
        }
        // Two minutes: a new dance every 4 to 6.5 s, a new mood every 7 to 12 s, never the same twice.
        assertTrue("moves $moves", moves in 18..30)
        assertTrue("moods $moods", moods in 10..17)
    }
}
