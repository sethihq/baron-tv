package com.barontv.remote.ui.playing

import com.barontv.remote.tv.MediaAction
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.Volume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Spotify's TV app: the one app with a cover here, and what the empty state offers to open. */
internal const val SPOTIFY = "com.spotify.tv.android"

/** What's playing is asked for every second, the volume every few (it changes less, and the slider shows it). */
internal const val NOW_EVERY_MS = 1_000L
internal const val VOLUME_EVERY_MS = 3_000L

/** The slider's levels go out at most about eight a second; the TV's own bar keeps up with that. */
internal const val SEND_EVERY_MS = 125L

/** After the slider moves, polls leave it alone this long, so an answer from before the move can't pull it back. */
internal const val QUIET_AFTER_SET_MS = 1_500L

/**
 * How long a pressed play or pause holds against polls that still say otherwise. The TV takes a moment to
 * act on the key and to report it; after this, a poll that disagrees wins, so a press that never reached
 * the music shows the truth again.
 */
internal const val PLAY_GRACE_MS = 2_500L

/** The bridge must stay silent this long before the tab says so, so a slow start or a blip doesn't flash it. */
internal const val NO_BRIDGE_AFTER_MS = 1_500L

/** Empty answers in a row before a playing song gives way to the empty state, so one lost answer doesn't. */
internal const val MISSES_BEFORE_IDLE = 2

/** What the Playing tab shows. */
internal sealed interface PlayingUi {
    /** Nothing has answered yet. The page stays empty, so no state flashes past before the first answer. */
    data object Waiting : PlayingUi

    /** The TV's new home (the bridge on port 5512) isn't answering, and what's playing comes from it. */
    data object NoBridge : PlayingUi

    /** The bridge answers and nothing is playing. */
    data object Idle : PlayingUi

    /** Something is playing or paused. [volume] is null until the TV has said what it is. */
    data class Playing(val now: NowPlaying, val volume: Volume?) : PlayingUi
}

/** What the Playing tab can ask of the TV. Each suspend call says whether the TV took it. */
internal interface PlayingControls {
    suspend fun playPause(): Boolean

    /** Next or previous. */
    suspend fun skip(action: MediaAction): Boolean

    suspend fun openSpotify(): Boolean

    /** A finger is on the volume slider: polls leave it alone until [releaseVolume]. */
    fun holdVolume()

    /** The slider is at [level] now (0 to 100), moved by a finger, a key or TalkBack. */
    fun setVolume(level: Int)

    fun releaseVolume()
}

/**
 * The Playing tab's state, kept from the TV's bridge. While [run] runs (only while the tab is on screen)
 * it asks what's playing every second and the volume every few seconds, and sends the slider's levels.
 *
 * Presses show at once: play and pause flip the button before the TV answers, and the polls correct it
 * if the TV disagrees for longer than [PLAY_GRACE_MS]. The slider's level is the finger's while it's down
 * and for a moment after, then the TV's again.
 *
 * Everything runs on the main thread (the composition's coroutines), so the fields need no locking.
 */
internal class PlayingModel(
    val bridge: TvBridge,
    initial: PlayingUi = PlayingUi.Waiting,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : PlayingControls {
    private val ui = MutableStateFlow(initial)
    val state: StateFlow<PlayingUi> = ui.asStateFlow()

    /** What the TV last said, before any press is laid over it. */
    private var told: NowPlaying? = (initial as? PlayingUi.Playing)?.now
    private var volume: Volume? = (initial as? PlayingUi.Playing)?.volume
    private var misses = 0
    private var silentSince = -1L

    /** A pressed play (true) or pause (false) that the TV hasn't confirmed yet, and when it was pressed. */
    private var wanted: Boolean? = null
    private var wantedAt = 0L

    private var held = false
    private var quietUntil = 0L
    private var lastLevel = -1

    /** The newest level the slider wants; older ones still waiting are dropped. */
    private val levels = Channel<Int>(Channel.CONFLATED)

    /** Polls and sends until cancelled. Call it only while the tab is on screen and the app in front. */
    suspend fun run() {
        coroutineScope {
            launch {
                while (true) {
                    refreshNow()
                    delay(NOW_EVERY_MS)
                }
            }
            launch {
                while (true) {
                    refreshVolume()
                    delay(VOLUME_EVERY_MS)
                }
            }
            launch {
                for (level in levels) sendLevel(level)
            }
        }
    }

    internal suspend fun refreshNow() {
        val askedAt = clock()
        val answer = bridge.nowPlaying()
        // An answer means the bridge is there, whatever its flag said a moment ago.
        if (answer == null && !bridge.available.value) {
            if (silentSince < 0) silentSince = askedAt
            if (clock() - silentSince >= NO_BRIDGE_AFTER_MS) show(PlayingUi.NoBridge)
            return
        }
        silentSince = -1
        if (answer == null) {
            misses++
            if (ui.value !is PlayingUi.Playing || misses >= MISSES_BEFORE_IDLE) {
                told = null
                wanted = null
                show(PlayingUi.Idle)
            }
            return
        }
        misses = 0
        told = answer
        show(PlayingUi.Playing(withWanted(answer, askedAt), volume))
    }

    internal suspend fun refreshVolume() {
        if (held || clock() < quietUntil) return
        val answer = bridge.volume() ?: return
        // A finger may have landed while the TV was answering.
        if (held || clock() < quietUntil) return
        showVolume(answer)
    }

    override suspend fun playPause(): Boolean {
        val shown = ui.value as? PlayingUi.Playing ?: return false
        val want = !shown.now.playing
        wanted = want
        wantedAt = clock()
        ui.value = shown.copy(now = shown.now.copy(playing = want))
        val sent = bridge.media(MediaAction.PLAY_PAUSE)
        // The key never reached the TV: show what it last said. A newer press keeps its own guess.
        if (!sent && wanted == want) {
            wanted = null
            val current = ui.value as? PlayingUi.Playing
            val truth = told
            if (current != null && truth != null) ui.value = current.copy(now = current.now.copy(playing = truth.playing))
        }
        return sent
    }

    override suspend fun skip(action: MediaAction): Boolean = bridge.media(action)

    override suspend fun openSpotify(): Boolean = bridge.launch(SPOTIFY)

    override fun holdVolume() {
        held = true
    }

    override fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, 100)
        lastLevel = clamped
        quietUntil = clock() + QUIET_AFTER_SET_MS
        showVolume(Volume(clamped, muted = volume?.muted ?: false))
        levels.trySend(clamped)
    }

    override fun releaseVolume() {
        held = false
        quietUntil = clock() + QUIET_AFTER_SET_MS
    }

    /** Sends one level, then waits out the rest of its slot, so the TV gets at most about eight a second. */
    private suspend fun sendLevel(level: Int) {
        val began = clock()
        val answer = bridge.setVolume(level)
        quietUntil = max(quietUntil, clock() + QUIET_AFTER_SET_MS)
        // The TV's answer counts once the finger is off and no newer level is on its way.
        if (answer != null && !held && level == lastLevel) showVolume(answer)
        delay((SEND_EVERY_MS - (clock() - began)).coerceAtLeast(0L))
    }

    /** [answer] with a pressed play or pause laid over it, until the TV agrees or the grace runs out. */
    private fun withWanted(answer: NowPlaying, askedAt: Long): NowPlaying {
        val want = wanted ?: return answer
        // Asked before the press: the TV can't have known about it yet.
        val stale = askedAt < wantedAt
        if (!stale && (answer.playing == want || clock() - wantedAt >= PLAY_GRACE_MS)) {
            wanted = null
            return answer
        }
        return answer.copy(playing = want)
    }

    private fun showVolume(level: Volume) {
        volume = level
        val shown = ui.value as? PlayingUi.Playing ?: return
        ui.value = shown.copy(volume = level)
    }

    private fun show(next: PlayingUi) {
        ui.value = next
    }
}
