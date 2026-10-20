package com.barontv.remote.ui.playing

import com.barontv.remote.tv.MediaAction
import com.barontv.remote.tv.Volume
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model and the fake TV in virtual time. Not through runTest: that fails on any stray exception left by
 * earlier tests in the same JVM (Paparazzi's), whoever's it is. The rig fails on the model's own instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(val tv: PlayingFakeBridge, initial: PlayingUi = PlayingUi.Waiting) {
    private val scope = TestScope()
    private val failures = mutableListOf<Throwable>()

    // Under a supervisor, so a failing poll reaches this handler instead of quietly cancelling the scope.
    private val caught = SupervisorJob(scope.coroutineContext[Job]) + CoroutineExceptionHandler { _, e -> failures += e }
    private var polls: Job? = null

    val model = PlayingModel(tv, initial, clock = { scope.testScheduler.currentTime })

    val shown get() = model.state.value
    val playing get() = (model.state.value as PlayingUi.Playing).now.playing
    val level get() = (model.state.value as PlayingUi.Playing).volume?.level

    /** Starts the polls and lets the first round answer. */
    fun start() = apply {
        polls = scope.launch(caught) { model.run() }
        scope.runCurrent()
    }

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** Runs one of the model's suspend calls (the fake answers at once) and gives back its answer. */
    fun <T> call(block: suspend PlayingModel.() -> T): T {
        val answer = scope.async { model.block() }
        scope.runCurrent()
        return answer.getCompleted()
    }

    fun stop() {
        polls?.cancel()
        scope.runCurrent()
        assertEquals("the model's coroutines failed", emptyList<Throwable>(), failures)
    }
}

private fun rig(tv: PlayingFakeBridge, initial: PlayingUi = PlayingUi.Waiting, test: Rig.() -> Unit) {
    val rig = Rig(tv, initial)
    rig.test()
    rig.stop()
}

/** The Playing tab's model against the fake TV: what it asks, when, and what it shows. */
class PlayingModelTest {
    @Test
    fun `asks what's playing every second and the volume every three, and stops when told`() {
        val tv = PlayingFakeBridge()
        val rig = Rig(tv).start()
        rig.advance(10_000)
        assertEquals(11, tv.nowAsked)
        assertEquals(4, tv.volumeAsked)
        rig.stop()
        rig.advance(10_000)
        assertEquals(11, tv.nowAsked)
        assertEquals(4, tv.volumeAsked)
    }

    @Test
    fun `shows what the TV says is playing, with its volume`() = rig(PlayingFakeBridge(PlayingSamples.Song, Volume(35, muted = false))) {
        start()
        val playing = shown as PlayingUi.Playing
        assertEquals(PlayingSamples.Song, playing.now)
        assertEquals(35, playing.volume?.level)
    }

    @Test
    fun `nothing playing is the empty state at once`() = rig(PlayingFakeBridge(now = null)) {
        start()
        assertEquals(PlayingUi.Idle, shown)
    }

    @Test
    fun `one empty answer while a song plays doesn't empty the tab, two do`() = rig(PlayingFakeBridge(PlayingSamples.Song)) {
        start()
        tv.now = null
        advance(NOW_EVERY_MS)
        assertTrue(shown is PlayingUi.Playing)
        advance(NOW_EVERY_MS)
        assertEquals(PlayingUi.Idle, shown)
    }

    @Test
    fun `a silent bridge says so only after a moment, and the song comes back when it answers`() =
        rig(PlayingFakeBridge(PlayingSamples.Song, available = false)) {
            start()
            assertEquals(PlayingUi.Waiting, shown)
            advance(NOW_EVERY_MS)
            assertEquals(PlayingUi.Waiting, shown)
            advance(NOW_EVERY_MS)
            assertEquals(PlayingUi.NoBridge, shown)
            tv.answer(true)
            advance(NOW_EVERY_MS)
            assertTrue(shown is PlayingUi.Playing)
        }

    @Test
    fun `pause shows at once and holds against polls from before the TV caught up`() = rig(PlayingFakeBridge(PlayingSamples.Song)) {
        start()
        assertTrue(playing)
        assertTrue(call { playPause() })
        assertFalse(playing)
        assertEquals(listOf(MediaAction.PLAY_PAUSE), tv.media)
        // The TV took the key but the music app hasn't paused yet.
        tv.now = tv.now!!.copy(playing = true)
        advance(NOW_EVERY_MS)
        assertFalse(playing)
        // Now it has.
        tv.now = tv.now!!.copy(playing = false)
        advance(NOW_EVERY_MS)
        assertFalse(playing)
    }

    @Test
    fun `a TV that still disagrees after the grace wins`() = rig(PlayingFakeBridge(PlayingSamples.Song)) {
        start()
        call { playPause() }
        tv.now = tv.now!!.copy(playing = true)
        advance(PLAY_GRACE_MS - 500)
        assertFalse(playing)
        advance(NOW_EVERY_MS)
        assertTrue(playing)
    }

    @Test
    fun `a press that never reached the TV goes back at once`() =
        rig(PlayingFakeBridge(PlayingSamples.Song), initial = playing(PlayingSamples.Song)) {
            tv.answer(false)
            assertFalse(call { playPause() })
            assertTrue(playing)
        }

    @Test
    fun `next and previous go to the TV as they are`() =
        rig(PlayingFakeBridge(PlayingSamples.Song), initial = playing(PlayingSamples.Song)) {
            assertTrue(call { skip(MediaAction.NEXT) })
            assertTrue(call { skip(MediaAction.PREVIOUS) })
            assertEquals(listOf(MediaAction.NEXT, MediaAction.PREVIOUS), tv.media)
        }

    @Test
    fun `open Spotify launches Spotify's TV app`() = rig(PlayingFakeBridge(now = null), initial = PlayingUi.Idle) {
        assertTrue(call { openSpotify() })
        assertEquals(listOf(SPOTIFY), tv.launched)
    }

    @Test
    fun `a drag sends about eight levels a second and always ends on the last`() =
        rig(PlayingFakeBridge(PlayingSamples.Song), initial = playing(PlayingSamples.Song)) {
            start()
            model.holdVolume()
            // A finger moving for a second, a new level every 10 ms.
            for (i in 1..100) {
                model.setVolume(40 + i / 2)
                advance(10)
            }
            model.releaseVolume()
            advance(500)
            assertTrue("sent ${tv.levels.size}: ${tv.levels}", tv.levels.size in 8..10)
            assertEquals(90, tv.levels.last())
            assertEquals(90, level)
        }

    @Test
    fun `polls leave the slider alone while it's held and just after`() =
        rig(PlayingFakeBridge(PlayingSamples.Song, Volume(40, muted = false)), initial = playing(PlayingSamples.Song)) {
            start()
            model.holdVolume()
            model.setVolume(70)
            advance(0)
            // Something else changes the TV's volume while the finger is still down.
            tv.level = Volume(15, muted = false)
            advance(VOLUME_EVERY_MS * 2)
            assertEquals(70, level)
            model.releaseVolume()
            advance(QUIET_AFTER_SET_MS - 100)
            assertEquals(70, level)
            // Then the TV's own level shows again.
            advance(VOLUME_EVERY_MS)
            assertEquals(15, level)
        }

    @Test
    fun `a level set by a key or TalkBack goes out once`() =
        rig(PlayingFakeBridge(PlayingSamples.Song), initial = playing(PlayingSamples.Song)) {
            start()
            model.setVolume(45)
            advance(SEND_EVERY_MS)
            assertEquals(listOf(45), tv.levels)
            assertEquals(45, level)
        }
}
