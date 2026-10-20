package com.barontv.remote.ui.playing

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.barontv.remote.tv.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The phone with its real status bar and three-button navigation bar, drawn edge to edge as the app does
 * (the same set-up as the Remote's snapshots, kept here so this tab's tests stand on their own).
 */
private fun playingPhone(device: DeviceConfig) = Paparazzi(
    deviceConfig = device,
    theme = "android:Theme.Material.NoActionBar.TranslucentDecor",
    showSystemUi = true,
    useDeviceResolution = true,
)

/**
 * The Playing tab in each of its states, under a stand-in header and above a stand-in tab bar, so it sits
 * where the app shell puts it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PlayingSnapshots(device: DeviceConfig) {
    @get:Rule
    val paparazzi = playingPhone(device)

    // The tab polls through repeatOnLifecycle, which needs Dispatchers.Main. Paparazzi's can be broken (it
    // is whenever a runTest ran earlier in the same JVM), and then the failure lands on the next runTest.
    // A test dispatcher that never runs keeps the polls still: each snapshot shows exactly the state it's
    // given, and nothing fails into another test.
    @Before
    fun stillPolls() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** Spotify, with the song read off its screen. */
    @Test
    fun song() = shoot(playing(PlayingSamples.Song), PlayingFakeBridge(PlayingSamples.Song))

    /** Spotify when the TV couldn't read the song: the app's name large, no times. */
    @Test
    fun spotifyUnread() = shoot(playing(PlayingSamples.SpotifyUnread), PlayingFakeBridge(PlayingSamples.SpotifyUnread))

    @Test
    fun paused() {
        val paused = PlayingSamples.Song.copy(playing = false)
        shoot(playing(paused), PlayingFakeBridge(paused))
    }

    @Test
    fun nothingPlaying() = shoot(PlayingUi.Idle, PlayingFakeBridge(now = null))

    @Test
    fun noBridge() = shoot(PlayingUi.NoBridge, PlayingFakeBridge(available = false))

    /** A finger on the volume slider: the track thickens. */
    @Test
    fun sliderHeld() = shoot(
        playing(PlayingSamples.Song, level = 52),
        PlayingFakeBridge(PlayingSamples.Song, level = Volume(52, muted = false)),
        held = true,
    )

    // The fake says what [ui] shows, so the snapshot is the same even where the polls do run.
    private fun shoot(ui: PlayingUi, bridge: PlayingFakeBridge, held: Boolean = false) =
        paparazzi.snapshot { PlayingPreview(ui, bridge, previewHeld = held) }
}

/** A Pixel 6 (411 by 914 dp), close to the prototype's 412 by 892. */
class PlayingPixel6SnapshotTest : PlayingSnapshots(DeviceConfig.PIXEL_6) {
    /** Any app but Spotify: a calm tile in its colour. */
    @Test
    fun otherApp() = paparazzi.snapshot {
        PlayingPreview(playing(PlayingSamples.YouTube), PlayingFakeBridge(PlayingSamples.YouTube))
    }
}

/** A Nexus 5 (360 by 640 dp): the gaps tighten and the cover gives way. */
class PlayingNexus5SnapshotTest : PlayingSnapshots(DeviceConfig.NEXUS_5)

/** A Pixel 6 on its side: the cover goes beside the controls. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayingLandscapeSnapshotTest {
    @get:Rule
    val paparazzi = playingPhone(
        DeviceConfig.PIXEL_6.copy(screenHeight = 1080, screenWidth = 2400, orientation = ScreenOrientation.LANDSCAPE),
    )

    @Before
    fun stillPolls() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun song() = paparazzi.snapshot {
        PlayingPreview(playing(PlayingSamples.Song), PlayingFakeBridge(PlayingSamples.Song))
    }
}
