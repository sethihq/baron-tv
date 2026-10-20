package com.barontv.home.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The ear: the prototype's onsets on made-up spectra, and on made-up sound heard as on the TV (8-bit mono,
 * the last 2048 samples, 60 times a second).
 */
class HearingTest {

    // ---- On spectra, as getByteFrequencyData gives them (1025 bins, 23.4 Hz apart) ----

    @Test
    fun aKickTrainInTheSpectrumIsFound() {
        val random = Random(1)
        val heard = Rec()
        val hearing = Hearing()
        val kicks = mutableListOf<Double>()
        for (f in 0 until 600) {
            val now = 1000.0 + f * 1000.0 / 60
            // A kick every half second: the low bins jump, then fall back over a few frames.
            val sinceKick = (f - 20).mod(30)
            if (f >= 20 && sinceKick == 0) kicks += now
            val lift = if (f >= 20 && sinceKick < 8) 80 - sinceKick * 10 else 0
            val spectrum = IntArray(1025) { k ->
                val wobble = random.nextInt(-3, 4)
                when (k) {
                    in 2..6 -> 120 + lift + wobble
                    in 171..427 -> 100 + wobble
                    else -> 0
                }
            }
            hearing.listen(spectrum, 48000.0 / 2048, now, heard)
        }
        assertEquals(kicks.size, heard.kicks.size)
        for (i in kicks.indices) assertEquals(kicks[i], heard.kicks[i], 0.5)
        assertEquals(0, heard.hats.size)
    }

    @Test
    fun aSteadySoundsFlickerIsNotAKick() {
        val random = Random(2)
        val heard = Rec()
        val hearing = Hearing()
        for (f in 0 until 600) {
            // Every bin at its level, wobbling a few steps frame to frame, as a held chord does.
            val spectrum = IntArray(1025) { k -> if (k in 2..6 || k in 171..427) 140 + random.nextInt(-4, 5) else 0 }
            hearing.listen(spectrum, 48000.0 / 2048, 1000.0 + f * 1000.0 / 60, heard)
        }
        assertEquals(0, heard.kicks.size)
        assertEquals(0, heard.hats.size)
    }

    @Test
    fun theBandsLevelsAreThePrototypes() {
        val heard = Rec()
        val hearing = Hearing()
        // Bass bins at 0.85 of full and highs at 0.25: full bass, no sparkle.
        val spectrum = IntArray(1025) { k -> if (k in 2..6) 217 else if (k in 171..427) 64 else 0 }
        hearing.listen(spectrum, 48000.0 / 2048, 1000.0, heard)
        assertEquals(1.0, heard.bass, 0.01)
        assertEquals(0.0, heard.high, 0.01)
    }

    // ---- On sound, as the TV hears it ----

    @Test
    fun kicksOverABassLineAreFound() {
        val kicks = (500 until 10_000 step 500).map { it.toDouble() }
        val heard = hear(mix(kicks = kicks, bass = 0.15))
        assertEquals(kicks.size, heard.kicks.size)
        for (k in heard.kicks) {
            // Heard on the next frame or so, never early.
            val lag = k - kicks.minBy { abs(it - k) }
            assertTrue("kick heard $lag ms off", lag in 0.0..40.0)
        }
    }

    @Test
    fun aSteadyBassNoteIsNotAKick() {
        assertEquals(0, hear(mix(bass = 0.5)).kicks.size)
    }

    @Test
    fun kicksAreAtLeast240MsApart() {
        val kicks = (500 until 6000 step 120).map { it.toDouble() }
        val heard = hear(mix(kicks = kicks, bass = 0.15))
        assertTrue(heard.kicks.size >= 10)
        for (i in 1 until heard.kicks.size) assertTrue(heard.kicks[i] - heard.kicks[i - 1] > 240)
    }

    @Test
    fun hatsComeFromTheHighs() {
        val hats = (750 until 10_000 step 500).map { it.toDouble() }
        val heard = hear(mix(hats = hats, bass = 0.15))
        assertTrue("heard ${heard.hats.size} of ${hats.size} hats", heard.hats.size >= hats.size - 1)
        assertEquals(0, heard.kicks.size)
        for (i in 1 until heard.hats.size) assertTrue(heard.hats[i] - heard.hats[i - 1] > 420)
    }

    @Test
    fun theTvsVolumeDoesntChangeTheBeat() {
        val kicks = (500 until 10_000 step 500).map { it.toDouble() }
        val loud = hear(mix(kicks = kicks, bass = 0.15))
        val quiet = hear(mix(kicks = kicks, bass = 0.15), volume = 0.125)
        assertEquals(kicks.size, loud.kicks.size)
        assertEquals(kicks.size, quiet.kicks.size)
    }

    @Test
    fun the8BitHissOfAnEmptyBandIsNotAKick() {
        // Only highs: the low band holds nothing but the 8-bit capture's own hiss, lifted by the level.
        val heard = hear(mix(pad = 0.02))
        assertEquals(0, heard.kicks.size)
    }

    @Test
    fun theBandsHearTheirOwnFrequencies() {
        val bass = hear(mix(bass = 0.5))
        assertTrue("bass ${bass.bass}", bass.bass > 0.9)
        assertTrue("highs ${bass.high}", bass.high < 0.05)
        val highs = hear(mix(pad = 0.02))
        assertTrue("bass ${highs.bass}", highs.bass < 0.05)
        assertTrue("highs ${highs.high}", highs.high > 0.5)
    }

    @Test
    fun bandsCoverThePrototypesBins() {
        // 2048 samples at 48 kHz: 23.4 Hz a bin, so 40 to 150 Hz is bins 2 to 6 and 4 to 10 kHz 171 to 427.
        val hz = 48000.0 / 2048
        assertEquals(2, Hearing.firstBin(40.0, hz))
        assertEquals(6, Hearing.lastBin(150.0, hz, 2048))
        assertEquals(171, Hearing.firstBin(4000.0, hz))
        assertEquals(427, Hearing.lastBin(10000.0, hz, 2048))
        // Never the DC bin, never past Nyquist.
        assertEquals(1, Hearing.firstBin(5.0, hz))
        assertEquals(1024, Hearing.lastBin(30000.0, hz, 2048))
    }

    @Test
    fun decibelsAsTheAnalyserMapsThem() {
        assertEquals(0, Hearing.byteOf(1e-5)) // -100 dB
        assertEquals(255, Hearing.byteOf(0.0316228)) // -30 dB
        assertEquals(127, Hearing.byteOf(5.62e-4)) // -65 dB, halfway
        assertEquals(0, Hearing.byteOf(0.0))
        assertEquals(255, Hearing.byteOf(1.0))
    }

    @Test
    fun onsetIsThePrototypes() {
        val onset = Onset(64)
        onset.reset(45)
        // A flat history: nothing stands out, until something does by more than the floor.
        repeat(20) { assertEquals(0.0, onset.push(0.01, 0.035), 0.0) }
        assertTrue(onset.push(0.2, 0.035) > 0.18)
        // Under the floor never counts, however much it stands out.
        val quiet = Onset(64)
        quiet.reset(45)
        repeat(20) { quiet.push(0.0, 0.035) }
        assertEquals(0.0, quiet.push(0.03, 0.035), 0.0)
    }

    @Test
    fun silenceIsNoticed() {
        val hearing = Hearing()
        val silent = ByteArray(2048) { 128.toByte() }
        val rec = Rec()
        hearing.listen(silent, 48000.0, 1000.0, rec)
        hearing.listen(silent, 48000.0, 1016.0, rec)
        assertEquals(1000.0, hearing.silentSince, 0.0)
        hearing.listen(ByteArray(2048) { if (it % 2 == 0) 130.toByte() else 126.toByte() }, 48000.0, 1032.0, rec)
        assertEquals(-1.0, hearing.silentSince, 0.0)
    }

    private class Rec : BeatListener {
        val kicks = mutableListOf<Double>()
        val hats = mutableListOf<Double>()
        var bass = 0.3
        var high = 0.0

        override fun kick(now: Double, amp: Double) {
            kicks += now
        }

        override fun hat(now: Double) {
            hats += now
        }

        override fun levels(bass: Double, high: Double, dt: Double, snap: Boolean) {
            this.bass = if (snap) bass else this.bass + (bass - this.bass) * EdgeLight.perFrame(0.2, dt)
            this.high = if (snap) high else this.high + (high - this.high) * EdgeLight.perFrame(0.25, dt)
        }
    }

    companion object {
        private const val RATE = 48000

        // The sound, frame by frame as the TV hears it: the last 2048 samples, in 8 bits, at 60 a second.
        private fun hear(signal: FloatArray, volume: Double = 1.0): Rec {
            val hearing = Hearing()
            hearing.start(2048, 60.0)
            val rec = Rec()
            val win = ByteArray(2048)
            var t = 1000.0 / 60
            while (true) {
                val end = (t / 1000 * RATE).toInt()
                if (end > signal.size) break
                if (end >= 2048) {
                    for (i in 0 until 2048) {
                        // As the Visualizer's integer path: shifted down to 8 bits, so rounded down.
                        val v = floor(signal[end - 2048 + i] * volume * 128).toInt().coerceIn(-128, 127)
                        win[i] = (v + 128).toByte()
                    }
                    hearing.listen(win, RATE.toDouble(), t, rec)
                }
                t += 1000.0 / 60
            }
            return rec
        }

        /**
         * Ten seconds of made-up music: kick drums (a pitch drop from 110 to 50 Hz, fading over 120 ms),
         * hi-hats (40 tones between 4.5 and 9.5 kHz, fading over 40 ms), a held bass note at 80 Hz, and a
         * held pad of high tones.
         */
        private fun mix(
            kicks: List<Double> = emptyList(),
            hats: List<Double> = emptyList(),
            bass: Double = 0.0,
            pad: Double = 0.0,
            seconds: Double = 10.0,
        ): FloatArray {
            val n = (seconds * RATE).toInt()
            val out = FloatArray(n)
            val random = Random(3)
            val partials = DoubleArray(40) { 4500.0 + random.nextDouble() * 5000.0 }
            val phases = DoubleArray(40) { random.nextDouble() * 2 * PI }
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                var v = bass * sin(2 * PI * 80 * t)
                if (pad > 0) for (p in partials.indices) v += pad * sin(2 * PI * partials[p] * t + phases[p])
                out[i] = v.toFloat()
            }
            for (k in kicks) {
                val start = (k / 1000 * RATE).toInt()
                for (j in 0 until (0.4 * RATE).toInt()) {
                    if (start + j >= n) break
                    val t = j.toDouble() / RATE
                    val phase = 2 * PI * (50 * t + 60 * 0.02 * (1 - exp(-t / 0.02)))
                    out[start + j] += (0.6 * sin(phase) * exp(-t / 0.12)).toFloat()
                }
            }
            for (h in hats) {
                val start = (h / 1000 * RATE).toInt()
                for (j in 0 until (0.2 * RATE).toInt()) {
                    if (start + j >= n) break
                    val t = j.toDouble() / RATE
                    var v = 0.0
                    for (p in partials.indices) v += sin(2 * PI * partials[p] * t + phases[p])
                    out[start + j] += (0.015 * v * exp(-t / 0.04)).toFloat()
                }
            }
            return out
        }
    }
}
