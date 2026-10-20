package com.barontv.remote.ui.tvtab

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.barontv.remote.ui.Tab
import com.barontv.remote.ui.apps.PreviewApp

// The TV tab inside the app, for Android Studio's preview pane. The dock list drags in interactive mode.

@Preview(name = "TV", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun TvPreview() {
    PreviewApp(Tab.Tv)
}

@Preview(name = "TV, no new home", device = Devices.PIXEL_6, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun TvWithoutTheNewHomePreview() {
    PreviewApp(Tab.Tv, answering = false)
}

@Preview(name = "TV, small phone", device = Devices.NEXUS_5, showSystemUi = true, backgroundColor = 0xFF000000)
@Composable
private fun TvSmallPhonePreview() {
    PreviewApp(Tab.Tv)
}
