package com.barontv.home.media

/**
 * The decisions behind now playing, kept free of Android types so they can be tested on the JVM: which
 * session to follow, what its state means, whether it counts as music, how small the art gets.
 * The numbers are PlaybackState's state constants and AudioAttributes' content types, which never change.
 */
internal object MediaPick {
    // PlaybackState.STATE_*.
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_FAST_FORWARDING = 4
    const val STATE_REWINDING = 5
    const val STATE_BUFFERING = 6
    const val STATE_ERROR = 7
    const val STATE_CONNECTING = 8
    const val STATE_SKIPPING_TO_PREVIOUS = 9
    const val STATE_SKIPPING_TO_NEXT = 10
    const val STATE_SKIPPING_TO_QUEUE_ITEM = 11

    /** AudioAttributes.CONTENT_TYPE_MUSIC. */
    const val CONTENT_TYPE_MUSIC = 2

    /** The longest side of the art we keep: the island shows it at 60 dp, the glow only needs its colour. */
    const val ART_MAX_PX = 256

    /** Spotify's apps: always music, whatever their players say. */
    private val MUSIC_APPS = setOf("com.spotify.tv.android", "com.spotify.music")

    /**
     * Moving forward, or about to: buffering and skipping are counted too, so the island's bars don't
     * drop to dots for the moment between two songs.
     */
    fun playing(state: Int): Boolean = when (state) {
        STATE_PLAYING, STATE_FAST_FORWARDING, STATE_REWINDING, STATE_BUFFERING, STATE_CONNECTING,
        STATE_SKIPPING_TO_PREVIOUS, STATE_SKIPPING_TO_NEXT, STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /** Worth showing: playing or paused. Stopped, failed or empty sessions hide the island. */
    fun shows(state: Int): Boolean = state == STATE_PAUSED || playing(state)

    /**
     * Which session to follow, from their states in MediaSessionManager's order (most recent first):
     * the first one playing, else the most recent one that is paused, else none.
     */
    fun pick(states: List<Int>): Int? {
        val playingAt = states.indexOfFirst { playing(it) }
        if (playingAt >= 0) return playingAt
        val pausedAt = states.indexOfFirst { shows(it) }
        return if (pausedAt >= 0) pausedAt else null
    }

    /**
     * Music, not video. Android 9 doesn't say which app a player belongs to (the uid is cleared for
     * ordinary apps), so this asks whether any player on the TV is playing music, and counts Spotify as
     * music whatever its players report.
     */
    fun isMusic(packageName: String, contentTypes: List<Int>): Boolean =
        packageName in MUSIC_APPS || contentTypes.any { it == CONTENT_TYPE_MUSIC }

    /** The art's size once it fits in [max] px a side, keeping its shape; never scaled up. */
    fun fit(width: Int, height: Int, max: Int = ART_MAX_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= max && height <= max) return width to height
        val scale = max.toFloat() / maxOf(width, height)
        return maxOf(1, Math.round(width * scale)) to maxOf(1, Math.round(height * scale))
    }
}
