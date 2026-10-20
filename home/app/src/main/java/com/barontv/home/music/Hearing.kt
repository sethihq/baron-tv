package com.barontv.home.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where what the ear hears goes: the light and the bot (MusicEngine). */
internal interface BeatListener {
    /** A kick at [now], [amp] up to 1. */
    fun kick(now: Double, amp: Double)

    /** A hi-hat (an off-beat) at [now]. */
    fun hat(now: Double)

    /** The bass and the highs as heard, 0 to 1, [dt] seconds since the last; [snap]: the first, jump there. */
    fun levels(bass: Double, high: Double, dt: Double, snap: Boolean)
}

/**
 * The prototype's ear (hear and onset in app.js), fed by the TV's own sound. Once a frame, as in the
 * prototype, it takes the last 2048 samples of what the TV plays (SoundStream) and does exactly what
 * the prototype's Web Audio analyser did with its 2048 (Blackman window, magnitude over N, smoothing 0.2,
 * decibels mapped from -100..-30 to 0..255), then exactly the prototype's bands and onsets: a kick is a
 * jump in 40 to 150 Hz over its recent average (spectral flux against an adaptive threshold), the highs'
 * jumps in 4 to 10 kHz are the hats, at least 240 ms between kicks and 420 ms between hats.
 *
 * Checked on the prototype's five demo songs, heard as the TV would hear them: it finds about three
 * quarters of the prototype's own kicks, as many as the prototype finds of its own when its frames fall
 * 8 ms later (its onsets hang on frame timing). The Visualizer's own FFT (8-bit, unwindowed, 1024
 * samples, 20 a second) found under 40% of them.
 */
internal class Hearing {
    private var size = MAX
    private var rateHz = FRAMES_HZ
    private val fft = Fft(MAX)
    private val re = DoubleArray(MAX)
    private val im = DoubleArray(MAX)
    private val window = DoubleArray(MAX)
    private val smooth = DoubleArray(MAX / 2 + 1)
    private val bins = IntArray(MAX / 2 + 1)
    private val prev = IntArray(MAX / 2 + 1)
    private val low = Onset(64)
    private val highs = Onset(64)
    private var first = true
    private var lastTime = 0.0
    private var lastKick = -1e9
    private var lastHat = -1e9

    /** The loudest the song has been lately (RMS of a window), so the TV's volume doesn't change the light. */
    private var loud = 0.0

    /** The last window's bands as the onsets saw them, 0 to 1: flux (how much louder) and level. */
    var lowFlux = 0.0
        private set
    var lowLevel = 0.0
        private set
    var highFlux = 0.0
        private set
    var highLevel = 0.0
        private set

    /** How much the last window's level was scaled, a quarter to 8 times. */
    var gain = 1.0
        private set

    /** Since when the sound has been digital silence (every sample exactly the middle), or -1. */
    var silentSince = -1.0
        private set

    init {
        start(MAX, FRAMES_HZ)
    }

    /**
     * A fresh start: [captureSize] samples a window (a power of two, at most 2048), heard [captureRateHz]
     * times a second.
     */
    fun start(captureSize: Int, captureRateHz: Double) {
        size = captureSize.coerceIn(64, MAX)
        rateHz = captureRateHz.coerceAtLeast(1.0)
        fft.prepare(size)
        // Web Audio's Blackman window (alpha 0.16).
        windowPower = 0.0
        for (i in 0 until size) {
            val x = 2.0 * PI * i / size
            window[i] = 0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)
            windowPower += window[i] * window[i]
        }
        smooth.fill(0.0)
        // The prototype kept 45 frames of history at 60 fps: three quarters of a second.
        val keep = (0.75 * rateHz).roundToInt().coerceIn(MIN_HISTORY, 64)
        low.reset(keep)
        highs.reset(keep)
        first = true
        lastKick = -1e9
        lastHat = -1e9
        loud = 0.0
        silentSince = -1.0
    }

    /**
     * One window: [wave] is 8-bit unsigned PCM (128 is silence), as the Visualizer gives it, sampled at
     * [sampleRateHz]; [now] is when it was heard, on the music clock.
     */
    fun listen(wave: ByteArray, sampleRateHz: Double, now: Double, out: BeatListener) {
        val n = min(size, wave.size)
        if (n < size) return
        val dt = if (first) 1.0 / rateHz else ((now - lastTime) / 1000.0).coerceIn(0.001, 0.5)
        lastTime = now

        // The samples, and how loud they are. Rounding to 8 bits shifts them half a step down wherever
        // there's sound, which the clean audio the prototype heard didn't: the window's mean comes off, or
        // the step from silence to sound would land in the lowest bins as a kick.
        var mean = 0.0
        var flat = true
        for (i in 0 until n) {
            val v = (wave[i].toInt() and 0xFF) - 128
            if (v != 0) flat = false
            re[i] = v / 128.0
            mean += re[i]
        }
        mean /= n
        var sum = 0.0
        for (i in 0 until n) {
            val s = re[i] - mean
            re[i] = s
            sum += s * s
        }
        if (flat) {
            if (silentSince < 0) silentSince = now
        } else {
            silentSince = -1.0
        }
        val rms = sqrt(sum / n)
        // Level: follow the song's loud parts (straight up, down slowly over half a minute) and bring them
        // to what the prototype's analyser heard, so the TV's volume doesn't change the light. A quiet
        // bridge stays quiet against the chorus, as it did in the prototype.
        loud = if (rms > loud) rms else loud * exp(-dt / 30.0)
        gain = (LOUD / max(loud, 1e-6)).coerceIn(MIN_GAIN, MAX_GAIN)
        for (i in 0 until n) {
            re[i] *= gain * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)

        // The analyser's smoothing was 0.2 a frame at 60 fps.
        val keep = exp(dt * 60.0 * ln(SMOOTHING))
        val hz = sampleRateHz / n
        decibels(firstBin(40.0, hz), lastBin(150.0, hz, n), n, keep)
        decibels(firstBin(4000.0, hz), lastBin(10000.0, hz, n), n, keep)
        // Its analyser heard clean audio; 8 bits add a hiss of their own (about -91 dB a bin, more with
        // the gain) whose flicker would read as kicks wherever a band is near silent, so the flux doesn't
        // count wobbles under it.
        val hiss = byteOf(STEP / sqrt(12.0) * gain * sqrt(windowPower) / n * HISS_MARGIN)
        onsets(hz, n, hiss, now, dt, out)
    }

    /**
     * What [listen] does after its FFT, on a spectrum given as getByteFrequencyData gives it (0 to 255 a
     * bin, [binHz] apart, at most 1025 bins): the prototype's bands and onsets, for testing on made-up
     * spectra.
     */
    fun listen(spectrum: IntArray, binHz: Double, now: Double, out: BeatListener) {
        val dt = if (first) 1.0 / rateHz else ((now - lastTime) / 1000.0).coerceIn(0.001, 0.5)
        lastTime = now
        val count = min(spectrum.size, bins.size)
        for (k in 0 until count) bins[k] = spectrum[k]
        onsets(binHz, (count - 1) * 2, 0, now, dt, out)
    }

    private fun onsets(hz: Double, n: Int, hiss: Int, now: Double, dt: Double, out: BeatListener) {
        val lowA = firstBin(40.0, hz)
        val lowB = lastBin(150.0, hz, n)
        val highA = firstBin(4000.0, hz)
        val highB = lastBin(10000.0, hz, n)
        if (first) {
            // Nothing to compare the first window with: no flux, no onset.
            copy(lowA, lowB)
            copy(highA, highB)
        }
        // The prototype's bands: the flux (how much louder than the last window) and the level, 0 to 1.
        band(lowA, lowB, hiss)
        lowFlux = bandFlux
        lowLevel = bandLevel
        band(highA, highB, hiss)
        highFlux = bandFlux
        highLevel = bandLevel
        copy(lowA, lowB)
        copy(highA, highB)

        val kick = low.push(lowFlux, 0.035)
        if (kick > 0 && now - lastKick > 240) {
            lastKick = now
            out.kick(now, min(1.0, 0.55 + kick * 5))
        }
        val hat = highs.push(highFlux, 0.02)
        if (hat > 0 && now - lastHat > 420 && now - lastKick > 120) {
            lastHat = now
            out.hat(now)
        }
        val bass = ((lowLevel - 0.45) / 0.4).coerceIn(0.0, 1.0)
        val high = ((highLevel - 0.25) / 0.45).coerceIn(0.0, 1.0)
        out.levels(bass, high, dt, first)
        first = false
    }

    private var bandFlux = 0.0
    private var bandLevel = 0.0
    private var windowPower = 0.0

    private fun band(a: Int, b: Int, hiss: Int) {
        var flux = 0.0
        var level = 0.0
        for (i in a..b) {
            val d = max(bins[i], hiss) - max(prev[i], hiss)
            if (d > 0) flux += d
            level += bins[i]
        }
        val n = (b - a + 1) * 255.0
        bandFlux = flux / n
        bandLevel = level / n
    }

    private fun decibels(a: Int, b: Int, n: Int, keep: Double) {
        for (k in a..b) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / n
            val s = keep * smooth[k] + (1 - keep) * mag
            smooth[k] = s
            bins[k] = byteOf(s)
        }
    }

    private fun copy(a: Int, b: Int) {
        for (k in a..b) prev[k] = bins[k]
    }

    companion object {
        /** The prototype's analyser window (fftSize 2048). */
        const val MAX = 2048

        /** The prototype heard once an animation frame; its onsets kept 45 of them, three quarters of a second. */
        const val FRAMES_HZ = 60.0
        private const val MIN_HISTORY = 8
        private const val SMOOTHING = 0.2
        private const val MIN_DB = -100.0
        private const val MAX_DB = -30.0

        /**
         * What the prototype's analyser heard of a song's loud parts: the RMS of its loudest 2048-sample
         * windows was 0.47 to 0.63 across the five demo songs' previews, played at volume 0.7.
         */
        const val LOUD = 0.40
        private const val MIN_GAIN = 0.25
        private const val MAX_GAIN = 8.0

        /** One step of the Visualizer's 8-bit samples, on the -1..1 scale. */
        private const val STEP = 1.0 / 128

        /** 7 dB over the 8-bit hiss's average: 99% of hiss-only bins sit under it. */
        private const val HISS_MARGIN = 2.24

        /** A bin's magnitude as getByteFrequencyData gives it: -100 dB is 0, -30 dB is 255. */
        fun byteOf(magnitude: Double): Int {
            val db = if (magnitude > 0) 20 * log10(magnitude) else -1000.0
            return floor(255.0 / (MAX_DB - MIN_DB) * (db - MIN_DB)).toInt().coerceIn(0, 255)
        }

        /** The FFT bins a band covers, as the prototype rounds them: the first and the last. */
        fun firstBin(hz: Double, binHz: Double): Int = max(1, (hz / binHz).roundToInt())

        fun lastBin(hz: Double, binHz: Double, size: Int): Int = min(size / 2, (hz / binHz).roundToInt())
    }
}

/**
 * The prototype's onset: a value counts when it stands above both a floor and its recent average by 1.6
 * standard deviations, and how far above the average it stands is how strong it was.
 */
internal class Onset(capacity: Int) {
    private val hist = DoubleArray(capacity)
    private var length = capacity
    private var count = 0
    private var head = 0

    fun reset(keep: Int) {
        length = keep.coerceIn(1, hist.size)
        count = 0
        head = 0
    }

    /** Adds [x] to the history and returns how far it stands above the average, or 0 if it doesn't count. */
    fun push(x: Double, floor: Double): Double {
        hist[head] = x
        head = (head + 1) % length
        if (count < length) count++
        var mean = 0.0
        for (i in 0 until count) mean += hist[i]
        mean /= count
        var v = 0.0
        for (i in 0 until count) {
            val d = hist[i] - mean
            v += d * d
        }
        val sd = sqrt(v / count)
        return if (x > max(floor, mean + 1.6 * sd)) x - mean else 0.0
    }
}

/** A radix-2 FFT, in place, with its tables made once per size. */
internal class Fft(max: Int) {
    private val cos = DoubleArray(max / 2)
    private val sin = DoubleArray(max / 2)
    private val rev = IntArray(max)
    private var n = 0

    fun prepare(size: Int) {
        if (size == n) return
        n = size
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var r = 0
            var v = i
            for (b in 0 until bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            rev[i] = r
        }
        for (i in 0 until n / 2) {
            cos[i] = kotlin.math.cos(2 * PI * i / n)
            sin[i] = -kotlin.math.sin(2 * PI * i / n)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cos[k]
                    val wi = sin[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}
