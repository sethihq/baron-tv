package com.barontv.home.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Stitching the Visualizer's overlapping captures back into the sound as it played. */
class SoundStreamTest {
    private val random = Random(9)
    private val sound = ByteArray(48_000) { random.nextInt(256).toByte() }

    private fun capture(end: Int) = sound.copyOfRange(end - 1024, end)

    @Test
    fun overlappingCapturesRebuildTheSound() {
        val stream = SoundStream()
        // A poll every 12 ms, up to 6 ms late, each the latest 1024 samples; the clock's guess a little off.
        var end = 1024
        var last = end
        stream.add(capture(end), 1024, 0)
        while (end < 40_000) {
            end += 576 + random.nextInt(0, 289)
            stream.add(capture(end), 1024, end - last + random.nextInt(-40, 41))
            last = end
        }
        val out = ByteArray(2048)
        assertTrue(stream.latest(out, 2048))
        assertArrayEquals(sound.copyOfRange(end - 2048, end), out)
        assertEquals(1, stream.breaks)
    }

    @Test
    fun aLatePollStartsAFreshRun() {
        val stream = SoundStream()
        val out = ByteArray(2048)
        stream.add(capture(2000), 1024, 0)
        stream.add(capture(2600), 1024, 600)
        assertTrue(stream.latest(out, 1600))
        // 1500 samples later: nothing overlaps, so there's a gap and no 2048 in a row yet.
        stream.add(capture(4100), 1024, 1500)
        assertFalse(stream.latest(out, 2048))
        assertEquals(2, stream.breaks)
        stream.add(capture(4700), 1024, 600)
        stream.add(capture(5300), 1024, 600)
        assertTrue(stream.latest(out, 2048))
        assertArrayEquals(sound.copyOfRange(5300 - 2048, 5300), out)
    }

    @Test
    fun silenceLinesUpByTheClock() {
        val stream = SoundStream()
        val quiet = ByteArray(1024) { 128.toByte() }
        stream.add(quiet, 1024, 0)
        stream.add(quiet, 1024, 600)
        val out = ByteArray(2048)
        assertFalse(stream.latest(out, 2048))
        stream.add(quiet, 1024, 600)
        assertTrue(stream.latest(out, 2048))
        assertEquals(0, stream.breaks - 1)
    }

    @Test
    fun theSameCaptureTwiceAddsNothing() {
        val stream = SoundStream()
        stream.add(capture(3000), 1024, 0)
        stream.add(capture(3000), 1024, 5)
        val out = ByteArray(1024)
        assertTrue(stream.latest(out, 1024))
        assertFalse(stream.latest(ByteArray(1025), 1025))
        assertArrayEquals(capture(3000), out)
    }
}
