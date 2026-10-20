package com.barontv.remote.ui

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
 * A pretend new home on the TV, for previews and tests. It starts as the real TV is: its six dock apps
 * (Netflix isn't installed), the three inputs with HDMI 1 plugged in, the volume at 40 and a song playing
 * on Spotify. It answers at once and remembers what it was asked to do. [answering] false is a TV whose
 * new home isn't there (the bridge's safe answers); [failing] true is one that is there but says no to
 * every action.
 */
class FakeTvBridge(
    answering: Boolean = true,
    apps: List<TvApp> = TheTvsApps,
    inputs: List<TvInput> = listOf(
        TvInput("HDMI 1", live = true),
        TvInput("HDMI 2", live = false),
        TvInput("AV", live = false),
    ),
    volume: Volume = Volume(level = 40, muted = false),
    nowPlaying: NowPlaying? = SongOnSpotify,
    var failing: Boolean = false,
) : TvBridge {
    private val up = MutableStateFlow(answering)
    override val available: StateFlow<Boolean> = up.asStateFlow()

    var apps: List<TvApp> = apps
        private set
    var inputs: List<TvInput> = inputs
        private set
    var volume: Volume = volume
        private set
    var nowPlaying: NowPlaying? = nowPlaying
        private set

    /** What reached the TV, oldest first. */
    val launched = mutableListOf<String>()
    val switched = mutableListOf<String>()
    val shortcuts = mutableListOf<Shortcut>()
    val docks = mutableListOf<List<String>>()
    val mediaActions = mutableListOf<MediaAction>()

    /** The new home starts or stops answering. */
    fun answer(on: Boolean) {
        up.value = on
    }

    private val acts: Boolean get() = up.value && !failing

    override suspend fun volume(): Volume? = if (up.value) volume else null

    override suspend fun changeVolume(step: VolumeStep): Volume? {
        if (!acts) return null
        volume = when (step) {
            VolumeStep.UP -> volume.copy(level = (volume.level + 5).coerceAtMost(100))
            VolumeStep.DOWN -> volume.copy(level = (volume.level - 5).coerceAtLeast(0))
            VolumeStep.MUTE -> volume.copy(muted = !volume.muted)
        }
        return volume
    }

    override suspend fun setVolume(level: Int): Volume? {
        if (!acts) return null
        volume = volume.copy(level = level.coerceIn(0, 100))
        return volume
    }

    override suspend fun inputs(): List<TvInput> = if (up.value) inputs else emptyList()

    override suspend fun switchInput(name: String): Boolean {
        if (!acts || inputs.none { it.name == name }) return false
        switched += name
        return true
    }

    override suspend fun apps(): List<TvApp> = if (up.value) apps else emptyList()

    override suspend fun launch(packageName: String): Boolean {
        if (!acts) return false
        launched += packageName
        return true
    }

    /** As the home does: known pinned apps take the new order, anything else is ignored. */
    override suspend fun setDock(packages: List<String>): Boolean {
        if (!acts) return false
        docks += packages
        val pinned = apps.filter { it.pinned }.associateBy { it.packageName }
        val ordered = packages.mapNotNull { pinned[it] }
        apps = ordered + apps.filter { it.pinned && it !in ordered } + apps.filterNot { it.pinned }
        return true
    }

    override suspend fun nowPlaying(): NowPlaying? = if (up.value) nowPlaying else null

    override suspend fun media(action: MediaAction): Boolean {
        if (!acts) return false
        mediaActions += action
        nowPlaying = nowPlaying?.let { if (action == MediaAction.PLAY_PAUSE) it.copy(playing = !it.playing) else it }
        return true
    }

    override suspend fun shortcut(shortcut: Shortcut): Boolean {
        if (!acts) return false
        shortcuts += shortcut
        return true
    }

    companion object {
        /** The real TV's dock: the prototype's order, without Netflix, which can't run there. */
        val TheTvsApps = listOf(
            TvApp("Prime Video", "com.amazon.amazonvideo.livingroom", pinned = true),
            TvApp("YouTube", "com.google.android.youtube.tv", pinned = true),
            TvApp("JioHotstar", "in.startv.hotstar", pinned = true),
            TvApp("Spotify", "com.spotify.tv.android", pinned = true),
            TvApp("App Store", "tv.cloudwalker.market", pinned = true),
            TvApp("Media Center", "com.cvte.tv.media", pinned = true),
        )

        /** The prototype's first song, a minute in. */
        val SongOnSpotify = NowPlaying(
            packageName = "com.spotify.tv.android",
            appName = "Spotify",
            playing = true,
            title = "Blinding Lights",
            artist = "The Weeknd",
            positionMs = 61_000,
            durationMs = 200_000,
        )
    }
}
