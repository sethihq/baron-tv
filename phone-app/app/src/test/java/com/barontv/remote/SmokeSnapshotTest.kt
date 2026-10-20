package com.barontv.remote

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.tv.TvState
import com.barontv.remote.ui.RemoteApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class SmokeSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun placeholder() {
        val remote = object : TvRemote {
            override val state = MutableStateFlow<TvState>(TvState.Searching)
            override fun send(key: TvKey) {}
            override fun retry() {}
        }
        paparazzi.snapshot { RemoteApp(remote) }
    }
}
