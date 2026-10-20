package com.barontv.home.music

/**
 * What the TV plays as one continuous run of 8-bit samples, stitched from the Visualizer's captures, so
 * the ear can look at the last 2048 as the prototype's analyser did. Each capture is the latest 1024
 * samples; polled every 12 ms, one overlaps the one before, and since both are read from the same capture
 * buffer the overlap is the same byte for byte, which says exactly how many samples are new.
 *
 * Captures arrive on the polling thread and the ear reads on the main thread, so both sides hold the
 * lock, each for a copy of a couple of kilobytes. Plain Kotlin, tested on the JVM.
 */
internal class SoundStream(private val capacity: Int = 8192) {
    private val ring = ByteArray(capacity)
    private var end = 0
    private var run = 0
    private val last = ByteArray(Hearing.MAX)
    private var lastSize = 0

    /** Captures that lined up with the one before, and ones that didn't (a gap, or the first). */
    var stitched = 0
        private set
    var breaks = 0
        private set

    @Synchronized
    fun reset() {
        end = 0
        run = 0
        lastSize = 0
        stitched = 0
        breaks = 0
    }

    /** A capture of [size] samples; [expectedNew] is about how many are new, from the time since the last. */
    @Synchronized
    fun add(capture: ByteArray, size: Int, expectedNew: Int) {
        val fresh = if (lastSize == size) newSamples(capture, size, expectedNew) else -1
        if (fresh < 0) {
            // Nothing lines up: a gap (the poll was late, or the first capture). A fresh run starts here.
            breaks++
            run = 0
            append(capture, 0, size)
        } else {
            stitched++
            append(capture, size - fresh, fresh)
        }
        System.arraycopy(capture, 0, last, 0, size)
        lastSize = size
    }

    /** Copies the newest [size] samples into [out] if that many have come in without a gap. */
    @Synchronized
    fun latest(out: ByteArray, size: Int): Boolean {
        if (run < size) return false
        val start = (end - size + capacity) % capacity
        val first = minOf(size, capacity - start)
        System.arraycopy(ring, start, out, 0, first)
        if (first < size) System.arraycopy(ring, 0, out, first, size - first)
        return true
    }

    private fun append(src: ByteArray, from: Int, count: Int) {
        for (k in 0 until count) {
            ring[end] = src[from + k]
            end = (end + 1) % capacity
        }
        run = minOf(run + count, capacity)
    }

    // How many samples of the capture are new: the shift that makes its start match the end of the last
    // one, the one nearest [expected] first (silence matches anywhere, and then the clock is right), no
    // further than SEARCH from it, so the lock is never held long. -1 if none leaves at least MIN_OVERLAP
    // samples matching.
    private fun newSamples(capture: ByteArray, size: Int, expected: Int): Int {
        val most = size - MIN_OVERLAP
        if (most < 0) return -1
        val e = expected.coerceIn(0, most)
        for (d in 0..SEARCH) {
            val up = e + d
            val down = e - d
            if (up > most && down < 0) break
            if (up <= most && matches(capture, size, up)) return up
            if (d > 0 && down >= 0 && matches(capture, size, down)) return down
        }
        return -1
    }

    private fun matches(capture: ByteArray, size: Int, shift: Int): Boolean {
        for (k in 0 until size - shift) {
            if (capture[k] != last[shift + k]) return false
        }
        return true
    }

    private companion object {
        /** Fewer matching samples than this could line up by chance. */
        const val MIN_OVERLAP = 64

        /** How far from the clock's guess to look: the Visualizer's read point is good to a millisecond or so. */
        const val SEARCH = 256
    }
}
