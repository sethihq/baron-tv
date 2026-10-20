package com.barontv.home.core

import android.graphics.Bitmap

/**
 * What's playing on the TV, from whichever app has the active media session (Spotify, YouTube...).
 * The island shows it, the glow and music mode take their colours from [art], music mode runs only when
 * [isMusic] and [playing].
 */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    /** The cover, already scaled down (at most 256 px a side) so holding it costs little. */
    val art: Bitmap?,
    val playing: Boolean,
    /** Position at [positionAtMs] (SystemClock.elapsedRealtime), so the current position can be worked out. */
    val positionMs: Long,
    val positionAtMs: Long,
    /** Playback speed while playing (1.0 normally). */
    val speed: Float,
    val durationMs: Long,
    /** Music, not video: decided from the audio's content type and the app (a film's soundtrack isn't music). */
    val isMusic: Boolean,
)

/**
 * The one place now-playing news goes through. The media part publishes; the island, the glow and
 * music mode listen. Main thread only.
 */
object NowPlayingHub {
    var current: NowPlaying? = null
        private set

    private val listeners = mutableListOf<(NowPlaying?) -> Unit>()

    fun publish(value: NowPlaying?) {
        if (value == current) return
        current = value
        for (l in listeners.toList()) l(value)
    }

    fun addListener(l: (NowPlaying?) -> Unit) {
        listeners += l
        l(current)
    }

    fun removeListener(l: (NowPlaying?) -> Unit) {
        listeners -= l
    }
}
