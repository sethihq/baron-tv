package com.barontv.home.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The whole home screen on the TV (1280 x 720 at 213 dpi) with fixture data: Open-Meteo's forecast saved
 * at 6.15 pm in Bengaluru, this week's TVmaze shelf with drawn stand-in pictures (real posters stay out
 * of the repo), and the dock as this TV has it (Netflix isn't installed). The side dock and island slots
 * are the other parts' and stay empty here.
 */
class HomeSnapshotTest {
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

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    /** Tuesday 20 October 2026, 6.15.42 pm in Bengaluru: the fixture forecast's own "now". */
    private val sixFifteen: Long = Calendar.getInstance(ist).apply {
        clear()
        set(2026, Calendar.OCTOBER, 20, 18, 15, 42)
    }.timeInMillis

    private fun fixture(name: String): String = javaClass.getResource("/home/$name")!!.readText()

    private fun screen(): Pair<View, HomeScreen> {
        val root = LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_home, null)
        val home = HomeScreen(paparazzi.context, NoHost, root, live = false, clock = { sixFifteen }, zone = ist)
        // Paparazzi's window is in touch mode, where these views can't take focus; the TV, with no
        // touchscreen, never is.
        for (p in home.shelf.posters) p.isFocusableInTouchMode = true
        return root to home
    }

    private fun HomeScreen.withApps(netflix: Boolean = false) {
        val installed = DockApps.PINNED.filter { netflix || it.packageName != "com.netflix.ninja" }.map { it.packageName to it.label }
        val apps = DockApps.order(installed)
        val (w, h) = coverSize(apps.size)
        val covers = apps.associate { it.packageName to Images.decodeResource(paparazzi.context.resources, it.cover, w, h, opaque = true) }
        showApps(apps, covers, covers.mapValues { VividColor.of(it.value) })
        for (t in dock.tiles) t.isFocusableInTouchMode = true
    }

    private fun HomeScreen.withWeather() {
        showWeather(Forecast.parse(fixture("open-meteo.json")))
    }

    /** This week's shelf from the TVmaze fixtures, with drawn pictures in its places' sizes. */
    private fun HomeScreen.withShelf() {
        val items = listOf(
            ShelfItem("Helluva Boss", "Animation · Comedy · Season 3 · Prime Video", "", "Prime Video", "com.amazon.amazonvideo.livingroom"),
            ShelfItem("Judy Justice", "Reality · Legal · Prime Video", "", "Prime Video", "com.amazon.amazonvideo.livingroom"),
            ShelfItem("Koffee With Karan", "Talk show · JioHotstar", "", "JioHotstar", "in.startv.hotstar"),
        )
        val pictures = listOf(
            picture(416, 255, 0xFF2A0F45.toInt(), 0xFFE0457B.toInt()),
            picture(165, 255, 0xFF101D2E.toInt(), 0xFF3B7FD0.toInt()),
            picture(165, 255, 0xFF2E1A08.toInt(), 0xFFE7A33E.toInt()),
        )
        showShelf(ShelfData(ShelfData.Source.TVMAZE, items), pictures, pictures.map { VividColor.of(it) })
    }

    /** A stand-in poster: a dark wash, a glow of colour and a bright disc, enough to read as a picture. */
    private fun picture(w: Int, h: Int, dark: Int, light: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), dark, light, Shader.TileMode.CLAMP) })
        canvas.drawPaint(Paint().apply { shader = RadialGradient(w * 0.3f, h * 0.35f, h * 0.5f, 0x66FFFFFF, 0, Shader.TileMode.CLAMP) })
        canvas.drawCircle(w * 0.68f, h * 0.62f, h * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() })
        return Images.cover(bitmap, w, h, opaque = true)
    }

    @Test
    fun youtubeFocused() {
        val (root, home) = screen()
        home.withWeather()
        home.withShelf()
        home.withApps()
        home.focusStart()
        paparazzi.snapshot(root)
    }

    @Test
    fun posterFocused() {
        val (root, home) = screen()
        home.withWeather()
        home.withShelf()
        home.withApps()
        home.focusOn(home.shelf.posters[0])
        paparazzi.snapshot(root)
    }

    @Test
    fun offline() {
        val (root, home) = screen()
        home.withApps()
        home.focusOn(home.shelf.posters[0])
        paparazzi.snapshot(root)
    }

    /** All seven of the prototype's apps, to hold against its screenshot tile for tile. */
    @Test
    fun sevenApps() {
        val (root, home) = screen()
        home.withWeather()
        home.withShelf()
        home.withApps(netflix = true)
        home.focusStart()
        paparazzi.snapshot(root)
    }
}
