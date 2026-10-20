package com.barontv.remote.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.barontv.remote.tv.TvState

// The Remote screen in each of its four states, for Android Studio's preview pane.

@Preview(name = "Connected", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun ConnectedPreview() {
    RemoteApp(FakeTvRemote(TvState.Connected(name = "Living room TV", host = "192.168.1.25")), FakeTvBridge())
}

@Preview(name = "Searching", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun SearchingPreview() {
    RemoteApp(FakeTvRemote(TvState.Searching), FakeTvBridge())
}

@Preview(name = "Not found", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun NotFoundPreview() {
    RemoteApp(FakeTvRemote(TvState.NotFound), FakeTvBridge())
}

@Preview(name = "No Wi-Fi", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun NoWifiPreview() {
    RemoteApp(FakeTvRemote(TvState.NoWifi), FakeTvBridge())
}

@Preview(name = "Small phone", device = Devices.NEXUS_5, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun SmallPhonePreview() {
    RemoteApp(FakeTvRemote(), FakeTvBridge())
}
