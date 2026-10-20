package com.barontv.home.side

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import org.junit.Rule
import org.junit.Test

/**
 * The side dock on the whole TV screen, in each state the prototype shows (its demo data as fixtures):
 * home with HDMI 1 live, a focused tile, Wi-Fi, Wi-Fi off, Sound at 35% and at 0%.
 */
class SideDockSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    private object Host : HomeHost {
        override fun toast(text: CharSequence) {}
        override fun launch(intent: Intent) = true
        override fun launchApp(packageName: String) = true
    }

    private fun screen(setUp: (SideDockView) -> Unit = {}): View {
        val root = LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_home, null)
        val dock = SideDockView(paparazzi.context, Host)
        dock.useSystem = false
        root.findViewById<FrameLayout>(R.id.sideDockSlot).addView(dock, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dock.showInputs(
            listOf(
                TvInput("com.mediatek.tvinput/.hdmi.HDMIInputService/HW4", "HDMI 1", true, Inputs.CONNECTED),
                TvInput("com.mediatek.tvinput/.hdmi.HDMIInputService/HW5", "HDMI 2", true, Inputs.DISCONNECTED),
                TvInput("com.mediatek.tvinput/.composite.CompositeInputService/HW3", "AV", false, Inputs.DISCONNECTED),
            ),
        )
        setUp(dock)
        return root
    }

    @Test
    fun home() {
        paparazzi.snapshot(screen())
    }

    @Test
    fun focusedTile() {
        paparazzi.snapshot(screen { dock -> focus(dock.tiles.first { it.labelText == "Wi-Fi" }) })
    }

    @Test
    fun wifi() {
        paparazzi.snapshot(
            screen { dock ->
                dock.open(SideDockView.WIFI, null, animate = false)
                dock.wifiView.render(DEMO, animate = false)
                focus(dock.wifiView.hero)
            },
        )
    }

    @Test
    fun wifiOff() {
        paparazzi.snapshot(
            screen { dock ->
                dock.open(SideDockView.WIFI, null, animate = false)
                dock.wifiView.render(WifiUi(enabled = false), animate = false)
                focus(dock.wifiView.switch)
            },
        )
    }

    @Test
    fun sound35() {
        paparazzi.snapshot(
            screen { dock ->
                dock.open(SideDockView.SOUND, null, animate = false)
                dock.soundView.setLevel(35, 100, muted = false, animate = false)
                focus(dock.soundView)
            },
        )
    }

    @Test
    fun sound0() {
        paparazzi.snapshot(
            screen { dock ->
                dock.open(SideDockView.SOUND, null, animate = false)
                dock.soundView.setLevel(0, 100, muted = false, animate = false)
                focus(dock.soundView)
            },
        )
    }

    /** Paparazzi's Android counts as touch mode, where a TV's views don't take focus; this one may. */
    private fun focus(v: View) {
        v.isFocusableInTouchMode = true
        check(v.requestFocus()) { "focus didn't take" }
    }

    private companion object {
        /** The prototype's demo networks (app.js NETWORKS), Home 5G in use. */
        val DEMO = WifiUi(
            enabled = true,
            current = Net("Home 5G", -50, true),
            others = listOf(
                Net("Home 2.4G", -52, true),
                Net("JioFiber-8812", -62, true),
                Net("Airtel_Xstream_4521", -64, true),
                Net("ACT-Fibernet-77", -78, true),
                Net("Cafe Guest", -80, false),
            ),
        )
    }
}
