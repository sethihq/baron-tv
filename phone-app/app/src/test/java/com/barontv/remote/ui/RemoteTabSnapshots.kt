package com.barontv.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.barontv.remote.tv.NoBridge
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvState
import org.junit.Rule
import org.junit.Test

/**
 * The Remote tab's content on its own, on black, at about the size a Pixel 6 leaves it between the app's
 * header and its tab bar (411 by 710 dp). The app shell's own snapshots show it in place.
 */
class RemoteTabSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.SHRINK,
        showSystemUi = false,
        useDeviceResolution = true,
    )

    @Test
    fun connected() = snapshot { RemoteTab(FakeTvRemote(LIVING_ROOM), NoBridge) }

    @Test
    fun searching() = snapshot { RemoteTab(FakeTvRemote(TvState.Searching), NoBridge) }

    @Test
    fun notFound() = snapshot { RemoteTab(FakeTvRemote(TvState.NotFound), NoBridge) }

    /** A finger held on the right edge: its caret lit, the light under the finger. */
    @Test
    fun rightEdgePressed() = snapshot {
        RemoteControls(
            state = LIVING_ROOM,
            onKey = {},
            onRetry = {},
            onOpenWifiSettings = {},
            pressedEdge = TvKey.RIGHT,
        )
    }

    private fun snapshot(content: @Composable () -> Unit) = paparazzi.snapshot {
        Box(Modifier.size(411.dp, 710.dp).background(Palette.Bg)) { content() }
    }

    private companion object {
        val LIVING_ROOM = TvState.Connected(name = "Living room TV", host = "192.168.1.25")
    }
}
