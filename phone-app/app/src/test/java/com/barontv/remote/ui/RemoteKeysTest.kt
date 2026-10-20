package com.barontv.remote.ui

import com.barontv.remote.tv.MediaAction
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.tv.Shortcut
import com.barontv.remote.tv.TvApp
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvInput
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.Volume
import com.barontv.remote.tv.VolumeStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the Remote tab's presses go: Volume and Mute to the home's bridge while it answers (so the TV
 * shows the home's volume bar), everything else, and volume without the bridge, to the remote as keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteKeysTest {
    private val remote = FakeTvRemote()
    private val bridge = RecordingBridge()
    private val keys = RemoteKeys(remote, bridge)

    /**
     * Runs [body] with the tab's volume worker going, as RemoteTab runs it while on screen. It runs in the
     * test itself rather than in the background scope, which advanceUntilIdle leaves alone.
     */
    private fun tabTest(body: suspend TestScope.() -> Unit) = runTest {
        val worker = launch { keys.run() }
        runCurrent()
        try {
            body()
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun `without the bridge every key is a key press, volume too`() = tabTest {
        val all = listOf(
            TvKey.UP, TvKey.OK, TvKey.BACK, TvKey.HOME, TvKey.INPUT, TvKey.MENU,
            TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, TvKey.MUTE, TvKey.PLAY_PAUSE,
        )
        all.forEach(keys::press)
        advanceUntilIdle()
        assertEquals(all, remote.sent)
        assertEquals(emptyList<VolumeStep>(), bridge.steps)
    }

    @Test
    fun `with the bridge, volume and mute go to it, in order, and the rest stay key presses`() = tabTest {
        bridge.available.value = true
        listOf(TvKey.VOLUME_UP, TvKey.UP, TvKey.VOLUME_DOWN, TvKey.MUTE, TvKey.OK).forEach(keys::press)
        advanceUntilIdle()
        assertEquals(listOf(VolumeStep.UP, VolumeStep.DOWN, VolumeStep.MUTE), bridge.steps)
        assertEquals(listOf(TvKey.UP, TvKey.OK), remote.sent)
    }

    @Test
    fun `the way follows the bridge as it comes and goes`() = tabTest {
        keys.press(TvKey.VOLUME_UP)
        bridge.available.value = true
        keys.press(TvKey.VOLUME_UP)
        bridge.available.value = false
        keys.press(TvKey.VOLUME_UP)
        advanceUntilIdle()
        assertEquals(listOf(TvKey.VOLUME_UP, TvKey.VOLUME_UP), remote.sent)
        assertEquals(listOf(VolumeStep.UP), bridge.steps)
    }

    @Test
    fun `a held key on a slow TV doesn't keep the volume moving after the finger lifts`() = tabTest {
        bridge.available.value = true
        bridge.answerMs = 1_000
        // Six repeats 160 ms apart while the first step is still on its way: two may wait, the rest drop.
        repeat(6) {
            keys.press(TvKey.VOLUME_UP)
            advanceTimeBy(HoldRepeat.EVERY_MS)
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(List(3) { VolumeStep.UP }, bridge.steps)
    }

    @Test
    fun `a bridge that throws doesn't stop the volume keys`() = tabTest {
        bridge.available.value = true
        bridge.failNext = true
        keys.press(TvKey.VOLUME_UP)
        keys.press(TvKey.VOLUME_DOWN)
        advanceUntilIdle()
        assertEquals(listOf(VolumeStep.UP, VolumeStep.DOWN), bridge.steps)
    }

    @Test
    fun `only the volume keys have steps`() {
        assertEquals(VolumeStep.UP, volumeStepOf(TvKey.VOLUME_UP))
        assertEquals(VolumeStep.DOWN, volumeStepOf(TvKey.VOLUME_DOWN))
        assertEquals(VolumeStep.MUTE, volumeStepOf(TvKey.MUTE))
        val others = TvKey.entries - setOf(TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, TvKey.MUTE)
        assertEquals(others.map { null }, others.map(::volumeStepOf))
    }
}

/** A bridge that notes the volume steps it's asked for, each taking [answerMs] on the test's clock. */
private class RecordingBridge : TvBridge {
    override val available = MutableStateFlow(false)
    val steps = mutableListOf<VolumeStep>()
    var answerMs = 30L
    var failNext = false

    override suspend fun changeVolume(step: VolumeStep): Volume? {
        steps += step
        delay(answerMs)
        if (failNext) {
            failNext = false
            error("a broken bridge")
        }
        return Volume(40, muted = false)
    }

    override suspend fun volume(): Volume? = null
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
