package com.barontv.remote.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.barontv.remote.tv.TvState
import com.barontv.remote.ui.FakeTvBridge
import com.barontv.remote.ui.FakeTvRemote
import com.barontv.remote.ui.Shell
import com.barontv.remote.ui.Tab
import com.barontv.remote.ui.tvtab.TvInputs

// The Apps tab inside the app, for Android Studio's preview pane. The tabs work in interactive mode.

/** The app on [start], with a pretend TV whose new home is [answering] (and has listed its apps if so). */
@Composable
internal fun PreviewApp(
    start: Tab,
    answering: Boolean = true,
    state: TvState = TvState.Connected("Baron TV", "192.168.1.25"),
) {
    var tab by remember { mutableStateOf(start) }
    val bridge = remember { FakeTvBridge(answering = answering) }
    val apps = remember { TvApps(bridge, bridge.apps.takeIf { answering }) }
    val inputs = remember { TvInputs(bridge, bridge.inputs.takeIf { answering }) }
    val remote = remember { FakeTvRemote(state) }
    Shell(remote = remote, bridge = bridge, apps = apps, inputs = inputs, tab = tab, onTab = { tab = it })
}

@Preview(name = "Apps", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun AppsPreview() {
    PreviewApp(Tab.Apps)
}

@Preview(name = "Apps, no new home", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun AppsWithoutTheNewHomePreview() {
    PreviewApp(Tab.Apps, answering = false)
}

@Preview(name = "Apps, TV not found", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun AppsTvNotFoundPreview() {
    PreviewApp(Tab.Apps, answering = false, state = TvState.NotFound)
}
