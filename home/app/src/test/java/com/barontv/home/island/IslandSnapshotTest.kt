package com.barontv.home.island

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The island on the whole TV screen, compact and open, with the prototype's first demo song. */
class IslandSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    private object Host : HomeHost {
        override fun toast(text: CharSequence) {}
        override fun launch(intent: Intent) = true
        override fun launchApp(packageName: String) = true
    }

    @After
    fun clear() {
        NowPlayingHub.publish(null)
    }

    private fun screen(open: Boolean): View {
        val root = LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_home, null)
        val song = NowPlaying(
            packageName = "com.spotify.tv.android",
            title = "Blinding Lights",
            artist = "The Weeknd",
            art = cover(),
            playing = true,
            positionMs = 7_400,
            // No time of report: the position stays put, so the snapshot always reads 0:07.
            positionAtMs = 0,
            speed = 1f,
            durationMs = 200_000,
            isMusic = true,
        )
        NowPlayingHub.publish(song)
        val island = IslandView(paparazzi.context, Host)
        island.peeks = false
        island.pill.frozenBarsAt = 0L
        island.onNowPlaying(song)
        if (open) {
            // Paparazzi's Android counts as touch mode, where a TV's views don't take focus; this one may.
            island.pill.isFocusableInTouchMode = true
            island.setOpenForTest(true)
            check(island.pill.isFocused) { "focus didn't take" }
        }
        root.findViewById<FrameLayout>(R.id.islandSlot).addView(island, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    @Test
    fun compact() {
        paparazzi.snapshot(screen(open = false))
    }

    @Test
    fun open() {
        paparazzi.snapshot(screen(open = true))
    }

    /** The prototype's drawn cover for its first song: hsl(350 72% 58%) to hsl(30 70% 24%), corner to corner. */
    private fun cover(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // The same two colours in HSV.
        val from = Color.HSVToColor(floatArrayOf(350f, 0.6855f, 0.8824f))
        val to = Color.HSVToColor(floatArrayOf(30f, 0.8235f, 0.408f))
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f, from, to, Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 256f, 256f, p)
        return b
    }
}
