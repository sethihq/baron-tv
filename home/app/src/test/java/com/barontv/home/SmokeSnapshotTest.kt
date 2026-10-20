package com.barontv.home

import android.view.LayoutInflater
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** The TV as Paparazzi sees it: 1280 x 720 at 213 dpi, which is the prototype's 960 x 540 dp. */
val BARON_TV = DeviceConfig(
    screenHeight = 720,
    screenWidth = 1280,
    xdpi = 213,
    ydpi = 213,
    orientation = ScreenOrientation.LANDSCAPE,
    density = Density.TV,
    ratio = com.android.resources.ScreenRatio.LONG,
    size = com.android.resources.ScreenSize.LARGE,
    keyboard = com.android.resources.Keyboard.NOKEY,
    navigation = com.android.resources.Navigation.DPAD,
)

class SmokeSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    @Test
    fun emptyLayout() {
        val root = LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_home, null)
        paparazzi.snapshot(root)
    }
}
