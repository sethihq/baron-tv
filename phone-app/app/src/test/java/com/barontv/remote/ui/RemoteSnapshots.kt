package com.barontv.remote.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.barontv.remote.tv.TvState
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi renders the phone with its real status bar and three-button navigation bar. The translucent
 * decor lets the app draw under both, as `enableEdgeToEdge()` does on the phone, so the screen's own
 * inset padding is what keeps it clear of them.
 */
internal fun phone(device: DeviceConfig) = Paparazzi(
    deviceConfig = device,
    theme = "android:Theme.Material.NoActionBar.TranslucentDecor",
    showSystemUi = true,
    useDeviceResolution = true,
)

internal val LivingRoomTv = TvState.Connected(name = "Living room TV", host = "192.168.1.25")

/** The Remote screen in each of its four states, on a Pixel 6. */
class RemoteScreenSnapshotTest {
    @get:Rule
    val paparazzi = phone(DeviceConfig.PIXEL_6)

    @Test
    fun connected() = snapshot(LivingRoomTv)

    @Test
    fun searching() = snapshot(TvState.Searching)

    @Test
    fun notFound() = snapshot(TvState.NotFound)

    @Test
    fun noWifi() = snapshot(TvState.NoWifi)

    private fun snapshot(state: TvState) = paparazzi.snapshot { RemoteApp(FakeTvRemote(state)) }
}

/** Connected on a small phone, a Nexus 5 (360 by 640 dp), where the spacing tightens. */
class SmallPhoneSnapshotTest {
    @get:Rule
    val paparazzi = phone(DeviceConfig.NEXUS_5)

    @Test
    fun connected() = paparazzi.snapshot { RemoteApp(FakeTvRemote(LivingRoomTv)) }
}
