package com.barontv.home.saver

import android.graphics.Color
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random

/** The DVD screensaver mid-flight, at the spot and colour of the prototype's reference frame (400, 200, red). */
class DvdSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    @Test
    fun midFlight() {
        val motion = DvdMotion(Random(1))
        motion.x = 400.0
        motion.y = 200.0
        motion.colour = 0
        val view = DvdView(paparazzi.context, motion)
        // The dream window's own black, which the view leaves to it.
        view.setBackgroundColor(Color.BLACK)
        paparazzi.snapshot(view)
    }
}
