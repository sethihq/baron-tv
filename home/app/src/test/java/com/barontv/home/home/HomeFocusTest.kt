package com.barontv.home.home

import android.content.Intent
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.View.FOCUS_DOWN
import android.view.View.FOCUS_LEFT
import android.view.View.FOCUS_RIGHT
import android.view.View.FOCUS_UP
import android.widget.FrameLayout
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub
import com.barontv.home.core.dpi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Where the remote's arrows take focus, with Android's own focus search over the real layout at the TV's
 * 1280 x 720. The side dock's tiles and the island's pill are other parts': stand-ins sit where the
 * prototype puts them (measured from it), so the moves between parts are checked too.
 */
class HomeFocusTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    @Before
    fun still() {
        HomeMotion.enabled = false
    }

    @After
    fun moving() {
        HomeMotion.enabled = true
    }

    private object NoHost : HomeHost {
        override fun toast(text: CharSequence) {}
        override fun launch(intent: Intent) = true
        override fun launchApp(packageName: String) = true
    }

    private lateinit var root: View
    private lateinit var home: HomeScreen
    private val side = mutableMapOf<String, View>()
    private var island: View? = null

    private fun build(withIsland: Boolean) {
        val c = paparazzi.context
        root = LayoutInflater.from(c).inflate(R.layout.activity_home, null)
        home = HomeScreen(c, NoHost, root, live = false)
        home.showApps(DockApps.order(DockApps.PINNED.filter { it.label != "Netflix" }.map { it.packageName to it.label }))
        // The prototype's side dock tiles, in screen dp; the slot starts at 672, 32.
        val slot = root.findViewById<FrameLayout>(R.id.sideDockSlot)
        for ((name, x, y, w, h) in listOf(
            Tile("HDMI 1", 677f, 63.2f, 70.7f, 101.7f), Tile("HDMI 2", 752.7f, 63.2f, 70.7f, 101.7f), Tile("AV", 828.3f, 63.2f, 70.7f, 101.7f),
            Tile("Wi-Fi", 677f, 195.1f, 108.5f, 99.3f), Tile("Picture", 790.5f, 195.1f, 108.5f, 99.3f),
            Tile("Sound", 677f, 299.3f, 108.5f, 99.3f), Tile("Settings", 790.5f, 299.3f, 108.5f, 99.3f),
        )) {
            val v = stand(name)
            slot.addView(v, FrameLayout.LayoutParams(c.dpi(w), c.dpi(h)).apply {
                leftMargin = c.dpi(x - 672f)
                topMargin = c.dpi(y - 32f)
            })
            side[name] = v
        }
        if (withIsland) {
            island = stand("island").also {
                root.findViewById<FrameLayout>(R.id.islandSlot).addView(it, FrameLayout.LayoutParams(c.dpi(167f), c.dpi(34f)).apply {
                    leftMargin = c.dpi(396.5f)
                    topMargin = c.dpi(32f)
                })
            }
        }
        // Paparazzi's window is in touch mode; the TV never is.
        for (v in focusables(root)) v.isFocusableInTouchMode = true
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1280, 720)
    }

    private data class Tile(val name: String, val x: Float, val y: Float, val w: Float, val h: Float)

    private fun stand(name: String) = View(paparazzi.context).apply {
        isFocusable = true
        contentDescription = name
    }

    private fun focusables(v: View): List<View> {
        val out = ArrayList<View>()
        v.addFocusables(out, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
        return out
    }

    /** As the window does it: the focused view's own search (the dock's rules), then Android's finder. */
    private fun move(from: View, direction: Int): View {
        val next = from.focusSearch(direction) ?: FocusFinder.getInstance().findNextFocus(root as android.view.ViewGroup, from, direction)
        return next ?: from
    }

    private fun name(v: View): String = when (v) {
        is AppTileView -> v.app.label
        is PosterView -> "poster ${home.shelf.posters.indexOf(v) + 1}"
        else -> v.contentDescription.toString()
    }

    private fun tile(label: String): View = home.dock.tiles.first { it.app.label == label }

    private fun poster(n: Int): View = home.shelf.posters[n - 1]

    private fun assertMove(from: View, direction: Int, to: String) {
        assertEquals("${name(from)} ${arrow(direction)}", to, name(move(from, direction)))
    }

    private fun arrow(d: Int) = when (d) {
        FOCUS_UP -> "Up"
        FOCUS_DOWN -> "Down"
        FOCUS_LEFT -> "Left"
        else -> "Right"
    }

    @Test
    fun theDockRowStopsAtItsEnds() {
        build(withIsland = false)
        assertMove(tile("Prime Video"), FOCUS_LEFT, "Prime Video")
        assertMove(tile("Media Center"), FOCUS_RIGHT, "Media Center") // never up to the side dock
        assertMove(tile("YouTube"), FOCUS_RIGHT, "JioHotstar")
        assertMove(tile("YouTube"), FOCUS_LEFT, "Prime Video")
        assertMove(tile("Spotify"), FOCUS_DOWN, "Spotify")
    }

    @Test
    fun upFromTheDockGoesToWhatsAbove() {
        build(withIsland = false)
        assertMove(tile("Prime Video"), FOCUS_UP, "poster 1")
        assertMove(tile("YouTube"), FOCUS_UP, "poster 1")
        assertMove(tile("JioHotstar"), FOCUS_UP, "poster 2")
        assertMove(tile("Spotify"), FOCUS_UP, "poster 3")
        assertMove(tile("App Store"), FOCUS_UP, "Sound")
        assertMove(tile("Media Center"), FOCUS_UP, "Settings")
    }

    @Test
    fun theShelfLeadsDownToTheDockAndRightToTheSideDock() {
        build(withIsland = false)
        assertMove(poster(1), FOCUS_RIGHT, "poster 2")
        assertMove(poster(1), FOCUS_LEFT, "poster 1")
        assertMove(poster(3), FOCUS_LEFT, "poster 2")
        assertMove(poster(3), FOCUS_RIGHT, "Wi-Fi")
        assertMove(poster(1), FOCUS_DOWN, "YouTube")
        assertMove(poster(3), FOCUS_DOWN, "Spotify")
    }

    @Test
    fun theSideDockLeadsBack() {
        build(withIsland = false)
        assertMove(side.getValue("Wi-Fi"), FOCUS_LEFT, "poster 3")
        assertMove(side.getValue("HDMI 1"), FOCUS_LEFT, "poster 3")
        assertMove(side.getValue("Sound"), FOCUS_DOWN, "App Store")
        assertMove(side.getValue("Settings"), FOCUS_DOWN, "Media Center")
    }

    @Test
    fun upFromTheShelfFindsTheIsland() {
        build(withIsland = true)
        assertMove(poster(1), FOCUS_UP, "island")
        assertMove(poster(2), FOCUS_UP, "island")
        assertMove(island!!, FOCUS_DOWN, "poster 2")
    }

    @Test
    fun theGlowFollowsFocus() {
        build(withIsland = true)
        val youtube = tile("YouTube") as AppTileView
        youtube.glowColor = 0xFFFF0C0C.toInt()
        home.focusOn(youtube)
        assertEquals(true, home.glow.isShowing)
        assertEquals(0xFFFF0C0C.toInt(), home.glow.artColor)
        // The side dock has no art of its own: the glow fades out there.
        home.focusOn(side.getValue("Wi-Fi"))
        assertEquals(false, home.glow.isShowing)
        // Back on a poster with no picture yet: grey, as the prototype.
        home.focusOn(poster(1))
        assertEquals(true, home.glow.isShowing)
        assertEquals(VividColor.FALLBACK, home.glow.artColor)
        // In the island it takes the colour of what's playing.
        val cover = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1DB954.toInt()) }
        try {
            NowPlayingHub.publish(NowPlaying("com.spotify.tv.android", "Song", "Artist", cover, true, 0, 0, 1f, 200_000, true))
            home.focusOn(island!!)
            assertEquals(VividColor.of(cover), home.glow.artColor)
        } finally {
            NowPlayingHub.publish(null)
        }
    }

    @Test
    fun withNoIslandUpFromTheShelfGoesToTheSideDock() {
        // As the prototype: the side dock's top row is the only thing above the shelf.
        build(withIsland = false)
        assertMove(poster(1), FOCUS_UP, "HDMI 1")
    }
}
