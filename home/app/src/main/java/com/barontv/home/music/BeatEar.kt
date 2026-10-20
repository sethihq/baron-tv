package com.barontv.home.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The TV's own sound, for the beat: a Visualizer on the output mix (session 0), so it hears whatever is
 * playing, Spotify or anything else. RECORD_AUDIO is granted over adb (pm grant). Made on entering music
 * mode and released on leaving.
 *
 * Its capture listener tops out at 20 captures a second of 1024 samples, too coarse to find the kicks the
 * prototype found, so a small thread reads the latest samples every 12 ms instead (getWaveForm, which
 * may be called at any rate) and [SoundStream] stitches them into one run. Once a frame, on the main
 * thread, [hear] gives the last 2048 samples to [Hearing], as the prototype's animation frame did. The
 * capture is taken as played, not normalised per buffer, which would flatten every kick; [Hearing] evens
 * out the level instead.
 */
internal class BeatEar(private val hearing: Hearing) {
    private val stream = SoundStream()
    private val window = ByteArray(Hearing.MAX)
    private var visualizer: Visualizer? = null
    private var poller: Thread? = null
    private var sampleRateHz = 48000.0
    private var size = Hearing.MAX
    private var captureSize = 1024
    private var starved = 0

    // Which start the polling thread belongs to: a thread from an earlier start stops at once, so it can't
    // touch the stream of the next.
    @Volatile
    private var generation = 0

    /** The Visualizer keeps refusing to hand over samples. */
    @Volatile
    var failing = false
        private set

    /** Why the last [start] failed, for the log. */
    var failure = ""
        private set

    /** The captures never lined up, so it hears one capture at a time (for the log). */
    var unstitched = false
        private set

    val listening: Boolean get() = visualizer != null

    /** Starts listening; false (with [failure] set) if the TV won't let it, and the tempo takes over. */
    fun start(context: Context): Boolean {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failure = "RECORD_AUDIO not granted (adb shell pm grant com.barontv.home android.permission.RECORD_AUDIO)"
            return false
        }
        var v: Visualizer? = null
        return try {
            v = Visualizer(0)
            v.captureSize = min(Visualizer.getCaptureSizeRange()[1], 1024)
            v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            check(v.setEnabled(true) == Visualizer.SUCCESS) { "enable refused" }
            captureSize = v.captureSize
            sampleRateHz = v.samplingRate / 1000.0
            size = Hearing.MAX
            starved = 0
            unstitched = false
            failing = false
            hearing.start(size, Hearing.FRAMES_HZ)
            stream.reset()
            visualizer = v
            val visualizerForThread = v
            val gen = ++generation
            poller = Thread({ poll(visualizerForThread, gen) }, "BaronEar").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: RuntimeException) {
            // No such effect, the session is busy, or the firmware says no.
            failure = "${e.javaClass.simpleName}: ${e.message}"
            try {
                v?.release()
            } catch (ignored: RuntimeException) {
            }
            false
        }
    }

    fun stop() {
        generation++
        poller?.interrupt()
        poller = null
        val v = visualizer ?: return
        visualizer = null
        // getWaveForm, setEnabled and release all hold the Visualizer's own lock, so a read in flight on
        // the polling thread finishes first.
        try {
            v.enabled = false
        } catch (ignored: RuntimeException) {
        }
        v.release()
    }

    /** Once a frame at [now]: the last 2048 samples (or, if captures never line up, the last capture). */
    fun hear(now: Double, out: BeatListener) {
        if (stream.latest(window, size)) {
            starved = 0
            hearing.listen(window, sampleRateHz, now, out)
        } else if (++starved > STARVED_FRAMES && size != captureSize && !failing) {
            // Two seconds without 2048 samples in a row: this firmware's captures don't overlap.
            size = captureSize
            unstitched = true
            hearing.start(size, Hearing.FRAMES_HZ)
        }
    }

    // The polling thread: the latest samples every 12 ms, into the stream.
    private fun poll(v: Visualizer, gen: Int) {
        val buf = ByteArray(v.captureSize)
        var lastAt = System.nanoTime()
        var errors = 0
        while (gen == generation) {
            val ok = try {
                v.getWaveForm(buf) == Visualizer.SUCCESS
            } catch (e: IllegalStateException) {
                false
            }
            val at = System.nanoTime()
            if (gen != generation) return
            if (ok) {
                stream.add(buf, buf.size, ((at - lastAt) * sampleRateHz / 1e9).roundToInt())
                errors = 0
            } else {
                errors++
            }
            failing = errors > FAILING_POLLS
            lastAt = at
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val POLL_MS = 12L
        const val STARVED_FRAMES = 120
        const val FAILING_POLLS = 40
    }
}
