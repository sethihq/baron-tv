package com.barontv.remote.ui

import androidx.compose.foundation.rememberScrollState
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.barontv.remote.tv.TvApp
import com.barontv.remote.tv.TvState
import com.barontv.remote.ui.apps.TvApps
import com.barontv.remote.ui.tvtab.DockDrag
import com.barontv.remote.ui.tvtab.TvInputs
import com.barontv.remote.ui.tvtab.TvTab
import org.junit.Rule
import org.junit.Test

// Everything a snapshot needs is made before it is drawn, so nothing in it needs remembering.

/**
 * Paparazzi with the phone's real status bar and three-button navigation bar; the translucent decor lets
 * the app draw under both, as `enableEdgeToEdge()` does, so the shell's own insets keep it clear.
 */
private fun shellPhone(device: DeviceConfig) = Paparazzi(
    deviceConfig = device,
    theme = "android:Theme.Material.NoActionBar.TranslucentDecor",
    showSystemUi = true,
    useDeviceResolution = true,
)

/** What the real app calls the TV. */
private val BaronTv = TvState.Connected(name = "Baron TV", host = "192.168.1.25")

/** An app the TV may list that the phone has no cover for. */
private val Kodi = TvApp("Kodi", "org.xbmc.kodi", pinned = false)

/**
 * The whole app on [tab]. With [listed], the TV's new home has already given its lists (as it has by
 * the time anyone opens a tab), so the first frame shows them.
 */
private fun Paparazzi.app(
    tab: Tab,
    state: TvState = BaronTv,
    bridge: FakeTvBridge = FakeTvBridge(),
    listed: Boolean = true,
) {
    val remote = FakeTvRemote(state)
    val apps = TvApps(bridge, if (listed) bridge.apps else null)
    val inputs = TvInputs(bridge, if (listed) bridge.inputs else null)
    snapshot { Shell(remote = remote, bridge = bridge, apps = apps, inputs = inputs, tab = tab, onTab = {}) }
}

/** The TV tab scrolled to its end: Customise home in full, or with a row mid-drag when [drag] is given. */
private fun Paparazzi.tvTabEnd(drag: DockDrag = DockDrag()) {
    val bridge = FakeTvBridge()
    val remote = FakeTvRemote(BaronTv)
    val apps = TvApps(bridge, bridge.apps)
    val inputs = TvInputs(bridge, bridge.inputs)
    val notices = Notices()
    snapshot {
        ShellFrame(remote = remote, tab = Tab.Tv, onTab = {}, notices = notices) { content ->
            TvTab(
                apps = apps,
                inputs = inputs,
                bridge = bridge,
                say = {},
                reselected = 0,
                modifier = content,
                scroll = rememberScrollState(Int.MAX_VALUE),
                drag = drag,
            )
        }
    }
}

/**
 * YouTube (second) lifted and dragged one and a half rows down, as in the prototype's reference shot:
 * the rows below have made room and the gap waits in the fourth place.
 */
private fun youTubeMidDrag() = DockDrag().apply {
    lift("com.google.android.youtube.tv", 1)
    moveBy(84f, FakeTvBridge.TheTvsApps.size)
}

/** The app's four tabs on a Pixel 6, with the states each tab has to handle. */
class ShellSnapshotTest {
    @get:Rule
    val paparazzi = shellPhone(DeviceConfig.PIXEL_6)

    /** The Remote tab is the remote-tab part's; here it is its placeholder, between header and tabs. */
    @Test
    fun remoteTab() = paparazzi.app(Tab.Remote)

    @Test
    fun appsTab() = paparazzi.app(Tab.Apps)

    /** The Playing tab is the playing-tab part's; here it is its placeholder. */
    @Test
    fun playingTab() = paparazzi.app(Tab.Playing)

    @Test
    fun tvTab() = paparazzi.app(Tab.Tv)

    @Test
    fun tvTabEnd() = paparazzi.tvTabEnd()

    @Test
    fun tvDockMidDrag() = paparazzi.tvTabEnd(youTubeMidDrag())

    /** No new home on the TV (the old launcher, or its service off): the prototype's seven stand in. */
    @Test
    fun appsWithoutTheNewHome() = paparazzi.app(Tab.Apps, bridge = FakeTvBridge(answering = false), listed = false)

    /** An app the phone has no art for gets a calm tile with its name. */
    @Test
    fun appsWithAnAppWithoutArt() =
        paparazzi.app(Tab.Apps, bridge = FakeTvBridge(apps = FakeTvBridge.TheTvsApps + Kodi))

    /** No TV at all: the grid dims, as the Remote tab's pad does, and a tap on the header looks again. */
    @Test
    fun appsTvNotFound() =
        paparazzi.app(Tab.Apps, state = TvState.NotFound, bridge = FakeTvBridge(answering = false), listed = false)

    /** No new home: everything on the TV tab needs it, so one quiet line says so. */
    @Test
    fun tvWithoutTheNewHome() = paparazzi.app(Tab.Tv, bridge = FakeTvBridge(answering = false), listed = false)
}

/** The same tabs on a small phone, a Nexus 5 (360 by 640 dp). */
class ShellSmallPhoneSnapshotTest {
    @get:Rule
    val paparazzi = shellPhone(DeviceConfig.NEXUS_5)

    @Test
    fun remoteTab() = paparazzi.app(Tab.Remote)

    @Test
    fun appsTab() = paparazzi.app(Tab.Apps)

    @Test
    fun playingTab() = paparazzi.app(Tab.Playing)

    @Test
    fun tvTab() = paparazzi.app(Tab.Tv)

    @Test
    fun tvDockMidDrag() = paparazzi.tvTabEnd(youTubeMidDrag())
}
