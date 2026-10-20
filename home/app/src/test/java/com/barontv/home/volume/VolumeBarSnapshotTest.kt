package com.barontv.home.volume

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import org.junit.Rule
import org.junit.Test

/**
 * The home's volume bar over the TV's whole screen, where the overlay puts it (the side dock's place),
 * at 35%, at 0% and muted at 35%.
 */
class VolumeBarSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    private fun screen(level: VolumeLevel): View {
        val context = paparazzi.context
        // The TV's screen, as the overlay reads it from the display (Paparazzi's resource metrics read portrait).
        val box = VolumePlace.box(BARON_TV.screenWidth, BARON_TV.screenHeight)
        val screen = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val bar = VolumeBarView(context)
        screen.addView(
            bar,
            FrameLayout.LayoutParams(box.width, box.height).apply {
                leftMargin = box.left
                topMargin = box.top
            },
        )
        bar.setLevel(level, animate = false)
        return screen
    }

    @Test
    fun volume35() {
        paparazzi.snapshot(screen(VolumeLevel(35, muted = false)))
    }

    @Test
    fun volume0() {
        paparazzi.snapshot(screen(VolumeLevel(0, muted = false)))
    }

    @Test
    fun muted() {
        paparazzi.snapshot(screen(VolumeLevel(35, muted = true)))
    }
}
