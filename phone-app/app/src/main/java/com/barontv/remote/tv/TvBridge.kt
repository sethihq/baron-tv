package com.barontv.remote.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Baron home screen's bridge on the TV (port 5512; the whole API is in home/BRIDGE.md): everything the
 * phone does beyond key presses. Every call is safe to make at any time: with no TV, or with the old
 * launcher (no bridge), it returns null, an empty list or false, or falls back to key presses where the
 * implementation says so.
 */
interface TvBridge {
    /** True while the TV's bridge answers (the new home is installed and running). */
    val available: StateFlow<Boolean>

    suspend fun volume(): Volume?

    /** Up or down by 5%, or mute toggled; the TV shows its own volume bar. Null if it couldn't. */
    suspend fun changeVolume(step: VolumeStep): Volume?

    /** Sets the volume (the Playing tab's slider), 0 to 100. */
    suspend fun setVolume(level: Int): Volume?

    suspend fun inputs(): List<TvInput>

    suspend fun switchInput(name: String): Boolean

    /** The TV's apps in its dock order: pinned first. */
    suspend fun apps(): List<TvApp>

    suspend fun launch(packageName: String): Boolean

    /** Saves the dock order (package names); the TV re-lays its dock at once. */
    suspend fun setDock(packages: List<String>): Boolean

    /** What's playing, or null when nothing is. */
    suspend fun nowPlaying(): NowPlaying?

    suspend fun media(action: MediaAction): Boolean

    suspend fun shortcut(shortcut: Shortcut): Boolean
}

data class Volume(val level: Int, val muted: Boolean)

enum class VolumeStep { UP, DOWN, MUTE }

data class TvInput(val name: String, val live: Boolean)

data class TvApp(val name: String, val packageName: String, val pinned: Boolean)

/** [title] and [artist] may be empty and the times -1: the TV can't always read them (BRIDGE.md, /now). */
data class NowPlaying(
    val packageName: String,
    val appName: String,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
)

enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

enum class Shortcut { PICTURE, SOUND, SETTINGS }

/** No bridge at all: the safe answers. For previews, tests, and until the real one is wired in. */
object NoBridge : TvBridge {
    override val available: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun volume(): Volume? = null
    override suspend fun changeVolume(step: VolumeStep): Volume? = null
    override suspend fun setVolume(level: Int): Volume? = null
    override suspend fun inputs(): List<TvInput> = emptyList()
    override suspend fun switchInput(name: String) = false
    override suspend fun apps(): List<TvApp> = emptyList()
    override suspend fun launch(packageName: String) = false
    override suspend fun setDock(packages: List<String>) = false
    override suspend fun nowPlaying(): NowPlaying? = null
    override suspend fun media(action: MediaAction) = false
    override suspend fun shortcut(shortcut: Shortcut) = false
}
