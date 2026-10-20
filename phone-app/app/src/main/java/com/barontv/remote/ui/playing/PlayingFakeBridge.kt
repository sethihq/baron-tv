package com.barontv.remote.ui.playing

import com.barontv.remote.tv.MediaAction
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.tv.Shortcut
import com.barontv.remote.tv.TvApp
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvInput
import com.barontv.remote.tv.Volume
import com.barontv.remote.tv.VolumeStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pretend TV for the Playing tab's previews, snapshots and tests. It answers at once and keeps to the
 * [TvBridge] contract: while it isn't [available] every call gets null or false. Play and pause flip
 * what's playing, the slider's level sticks, and every request is written down, oldest first.
 */
internal class PlayingFakeBridge(
    var now: NowPlaying? = PlayingSamples.Song,
    var level: Volume = Volume(40, muted = false),
    available: Boolean = true,
) : TvBridge {
    private val up = MutableStateFlow(available)
    override val available: StateFlow<Boolean> = up.asStateFlow()

    val media = mutableListOf<MediaAction>()
    val levels = mutableListOf<Int>()
    val launched = mutableListOf<String>()

    /** How many times the tab asked what's playing, and the volume. */
    var nowAsked = 0
        private set
    var volumeAsked = 0
        private set

    fun answer(on: Boolean) {
        up.value = on
    }

    override suspend fun volume(): Volume? {
        volumeAsked++
        return if (up.value) level else null
    }

    override suspend fun changeVolume(step: VolumeStep): Volume? = null

    override suspend fun setVolume(level: Int): Volume? {
        if (!up.value) return null
        levels += level
        this.level = Volume(level.coerceIn(0, 100), muted = false)
        return this.level
    }

    override suspend fun inputs(): List<TvInput> = emptyList()

    override suspend fun switchInput(name: String) = false

    override suspend fun apps(): List<TvApp> = emptyList()

    override suspend fun launch(packageName: String): Boolean {
        if (!up.value) return false
        launched += packageName
        return true
    }

    override suspend fun setDock(packages: List<String>) = false

    override suspend fun nowPlaying(): NowPlaying? {
        nowAsked++
        return if (up.value) now else null
    }

    override suspend fun media(action: MediaAction): Boolean {
        val playing = now ?: return false
        if (!up.value) return false
        media += action
        if (action == MediaAction.PLAY_PAUSE) now = playing.copy(playing = !playing.playing)
        return true
    }

    override suspend fun shortcut(shortcut: Shortcut) = false
}

/** What the fake plays: the prototype's first song, and what the TV usually manages to read. */
internal object PlayingSamples {
    /** Spotify with the song read off its screen, 1:03 into 3:20, as the prototype opens. */
    val Song = NowPlaying(
        packageName = SPOTIFY,
        appName = "Spotify",
        playing = true,
        title = "Blinding Lights",
        artist = "The Weeknd",
        positionMs = 63_000,
        durationMs = 200_000,
    )

    /** Spotify when the TV couldn't read the song: no title, no artist, no times. */
    val SpotifyUnread = NowPlaying(
        packageName = SPOTIFY,
        appName = "Spotify",
        playing = true,
        title = "",
        artist = "",
        positionMs = -1,
        durationMs = -1,
    )

    /** Another app, which gets a calm tile in its colour. */
    val YouTube = NowPlaying(
        packageName = "com.google.android.youtube.tv",
        appName = "YouTube",
        playing = true,
        title = "",
        artist = "",
        positionMs = -1,
        durationMs = -1,
    )
}
