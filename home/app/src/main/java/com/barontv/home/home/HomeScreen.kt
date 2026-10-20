package com.barontv.home.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.barontv.home.BuildConfig
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.NowPlayingHub
import com.barontv.home.core.Tokens
import com.barontv.home.core.dpi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The home screen's own parts, in the slots activity_home.xml keeps for them: the glow behind everything,
 * the bento clock, the shelf, the app dock and the toast. It loads their data and keeps it fresh: the
 * weather every 30 minutes, the shelf every 4 hours, both from the cache first so they show at once after a
 * reboot and stay up offline; nothing touches the disk or the network on the main thread.
 *
 * The glow follows focus: the focused poster's or app's art, the now-playing art while focus is in the
 * island, and it fades out while focus is in the side dock.
 *
 * [live] false (snapshot tests) loads nothing: the test hands it data with [showWeather], [showShelf] and
 * [showApps], and [clock] and [zone] fix the time.
 */
class HomeScreen(
    private val context: Context,
    private val host: HomeHost,
    private val root: View,
    private val live: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    zone: TimeZone = TimeZone.getDefault(),
) {
    val glow = GlowView(context)
    val bento = BentoView(context)
    val shelf = ShelfView(context)
    val dock = DockView(context)
    val toast = ToastView(context)

    private val islandSlot: View = root.findViewById(R.id.islandSlot)
    private val sideDockSlot: View = root.findViewById(R.id.sideDockSlot)
    private val shelfSlot: FrameLayout = root.findViewById(R.id.shelfSlot)
    private val dockSlot: FrameLayout = root.findViewById(R.id.dockSlot)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = Http()

    // Made on first use, which is only ever in a live home screen (the cache directory is the app's).
    private val weatherSource by lazy { WeatherSource(context.cacheDir, http) }
    private val shelfSource by lazy { ShelfSource(context.cacheDir, http, BuildConfig.TMDB_KEY) }
    private val shelfKind = if (BuildConfig.TMDB_KEY.isNotEmpty()) ShelfData.Source.TMDB else ShelfData.Source.TVMAZE
    private var resumed = false

    // ---- Lifecycle --------------------------------------------------------------------------------------

    /** After every part is in place (onCreate): the apps, focus on YouTube, then the caches and network. */
    fun start() {
        if (!live) return
        loadApps()
        focusStart()
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packageReceiver, packages, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(packageReceiver, packages)
        DockOrder.addListener(dockOrderListener)
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        loadWeather()
        loadShelf()
    }

    /** On screen: the clock ticks and the data is checked; nothing runs while another app is in front. */
    fun resume() {
        resumed = true
        startClock()
        if (live) {
            loadWeather()
            loadShelf()
            handler.removeCallbacks(refresher)
            handler.postDelayed(refresher, CHECK_MS)
        }
    }

    fun pause() {
        resumed = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(refresher)
        handler.removeCallbacks(retryWeather)
        handler.removeCallbacks(retryShelf)
    }

    fun destroy() {
        pause()
        scope.cancel()
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        NowPlayingHub.removeListener(nowPlayingListener)
        DockOrder.removeListener(dockOrderListener)
        if (live) {
            try {
                context.unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // Never registered: start() didn't run.
            }
            try {
                connectivity?.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                // Never registered.
            }
        }
    }

    fun toast(text: CharSequence) = toast.show(text)

    /**
     * Something covers the whole screen while the activity stays resumed (music mode's black, the
     * screensaver): the clock stops ticking under it, and starts again on the second when it goes.
     */
    fun setCovered(covered: Boolean) {
        handler.removeCallbacks(ticker)
        if (!covered && resumed) startClock()
    }

    private fun startClock() {
        handler.removeCallbacks(ticker)
        // Back from another app: the second hand goes straight to the time, not round the missed seconds,
        // and the time zone is read again in case it was changed in Settings meanwhile.
        bento.dial.reset()
        if (live) {
            calendar.timeZone = TimeZone.getDefault()
            minuteStamp = -1
            dayStamp = -1
        }
        handler.post(ticker)
    }

    /**
     * Focus on YouTube, as the prototype starts (the first app if YouTube isn't here). Before the window
     * is up no focus news goes out, so the glow is told here; it waits for the cover, so it starts in
     * YouTube's red rather than grey.
     */
    fun focusStart() {
        val tile = dock.tileFor(DockApps.FIRST_FOCUS) ?: dock.tiles.firstOrNull() ?: return
        tile.requestFocus()
        if (tile.hasArt) glow.show(tile.glowColor)
    }

    /** Moves focus to [view] by hand, the glow with it (needed before the window is up, as in tests). */
    fun focusOn(view: View) {
        view.requestFocus()
        glowFor(view)
    }

    // ---- The glow follows focus ---------------------------------------------------------------------------

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused -> glowFor(focused) }

    private fun glowFor(focused: View?) {
        when {
            focused == null -> Unit
            focused is ArtView -> glow.show(focused.glowColor)
            focused.isInside(islandSlot) -> glow.show(nowPlayingColor())
            focused.isInside(sideDockSlot) -> glow.hide()
        }
    }

    private val nowPlayingListener: (NowPlaying?) -> Unit = {
        if (root.findFocus()?.isInside(islandSlot) == true) glow.show(nowPlayingColor())
    }

    private var nowPlayingArt: Bitmap? = null
    private var nowPlayingArtColor = VividColor.FALLBACK

    private fun nowPlayingColor(): Int {
        val art = NowPlayingHub.current?.art
        if (art !== nowPlayingArt) {
            nowPlayingArt = art
            nowPlayingArtColor = VividColor.of(art)
        }
        return nowPlayingArtColor
    }

    private fun View.isInside(ancestor: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    // ---- The clock --------------------------------------------------------------------------------------

    private val calendar = Calendar.getInstance(zone)
    private val hourFormat = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).apply { timeZone = Weather.ZONE }
    private var minuteStamp = -1L
    private var dayStamp = -1
    private var hourKey = ""
    private var forecast: Forecast? = null
    private var reading: WeatherReading? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            // Next on the second, a few ms late so it lands in the new second.
            handler.postDelayed(this, 1000 - clock() % 1000 + 5)
        }
    }

    private fun updateClock() {
        val now = clock()
        calendar.timeInMillis = now
        val h = calendar.get(Calendar.HOUR_OF_DAY)
        val m = calendar.get(Calendar.MINUTE)
        bento.dial.tick(h, m, calendar.get(Calendar.SECOND))
        val minute = now / 60_000
        if (minute == minuteStamp) return
        minuteStamp = minute
        bento.time.setTime(h, m)
        var lineChanged = false
        val key = hourFormat.format(now)
        if (key != hourKey) {
            // A new hour: the forecast already holds it, so "now" and "next" move on without a fetch.
            hourKey = key
            reading = forecast?.readingAt(key)
            bento.sky.setWeather(reading)
            lineChanged = true
        }
        val day = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        if (day != dayStamp) {
            dayStamp = day
            lineChanged = true
        }
        if (lineChanged) {
            val weekday = SimpleDateFormat("EEE", Locale.UK).apply { timeZone = calendar.timeZone }.format(now).uppercase(Locale.UK)
            bento.line.setLine(weekday, calendar.get(Calendar.DAY_OF_MONTH), reading)
        }
    }

    // ---- Weather ----------------------------------------------------------------------------------------

    /** Shows [forecast] (null: none yet) for the hour it is now. */
    fun showWeather(forecast: Forecast?) {
        this.forecast = forecast
        hourKey = ""
        minuteStamp = -1
        updateClock()
    }

    private var weatherJob: Job? = null
    private val retryWeather = Runnable { loadWeather() }

    private fun loadWeather() {
        if (weatherJob?.isActive == true) return
        weatherJob = scope.launch {
            if (forecast == null) withContext(Dispatchers.IO) { weatherSource.cached() }?.let { showWeather(it) }
            val age = withContext(Dispatchers.IO) { weatherSource.age(clock()) }
            if (age < Weather.EVERY_MS || !online()) return@launch
            try {
                showWeather(withContext(Dispatchers.IO) { weatherSource.fetch() })
            } catch (e: IOException) {
                // Offline or Open-Meteo hiccuped: keep what's shown; with nothing yet, try again soon.
                if (reading == null && resumed) handler.postDelayed(retryWeather, RETRY_MS)
            }
        }
    }

    // ---- The shelf --------------------------------------------------------------------------------------

    private var shelfData: ShelfData? = null
    private var shelfComplete = false
    private var shelfJob: Job? = null
    private val retryShelf = Runnable { loadShelf() }

    /**
     * Shows [data]'s posters with their [pictures] and the pictures' vivid [colors]; null data shows the
     * calm empty state: the film slates and one quiet line.
     */
    fun showShelf(data: ShelfData?, pictures: List<Bitmap?>, colors: List<Int>) {
        val source = data?.source ?: shelfKind
        val head = context.getString(if (source == ShelfData.Source.TMDB) R.string.home_shelf_trending else R.string.home_shelf_new)
        val credit = when {
            data == null -> ""
            source == ShelfData.Source.TMDB -> context.getString(R.string.home_credit_tmdb)
            else -> context.getString(R.string.home_credit_tvmaze)
        }
        shelf.setHead(head, credit)
        for ((i, poster) in shelf.posters.withIndex()) {
            val item = data?.items?.getOrNull(i)
            if (item == null) {
                poster.setText(if (i == 0) context.getString(R.string.home_shelf_waiting) else "", "")
                poster.onPress = null
            } else {
                poster.setText(item.title, item.meta)
                poster.onPress = { host.launchApp(item.packageName) }
            }
            poster.setArt(pictures.getOrNull(i))
            poster.glowColor = colors.getOrNull(i) ?: VividColor.FALLBACK
            if (poster.isFocused) glow.show(poster.glowColor)
        }
    }

    /** The art size (px) of each poster, from the slot's size, so pictures are cut before layout. */
    private fun posterSizes(): List<Pair<Int, Int>> {
        val slot = shelfSlot.layoutParams
        val g = ShelfView.geometry(context, slot.width.toFloat(), slot.height.toFloat())
        return List(3) { g.widths[it].roundToInt() to g.artHeight.roundToInt() }
    }

    private fun loadShelf() {
        if (shelfJob?.isActive == true) return
        shelfJob = scope.launch {
            if (shelfData == null) withContext(Dispatchers.IO) { shelfSource.cached() }?.let { present(it, online = false) }
            val now = clock()
            val stale = withContext(Dispatchers.IO) { shelfSource.age(now) } >= ShelfSource.EVERY_MS
            val quiet = shelfQuietUntil - now in 1..QUIET_MS
            if ((!stale && shelfComplete) || quiet || !online()) return@launch
            try {
                val data = shelfData.takeUnless { stale }
                    ?: withContext(Dispatchers.IO) { shelfSource.fetch(now, installedPackages) }
                present(data, online = true)
                // A picture that still won't come (gone from its server) isn't asked for again for a while.
                if (!shelfComplete) shelfQuietUntil = clock() + QUIET_MS
            } catch (e: ShelfSource.NotEnoughShows) {
                // Fewer than three shows this week: asking again in a minute won't change that.
                shelfQuietUntil = clock() + QUIET_MS
            } catch (e: IOException) {
                // Offline or the service hiccuped: keep the shelf that's up; with none yet, try again soon.
                if (shelfData == null && resumed) handler.postDelayed(retryShelf, RETRY_MS)
            }
        }
    }

    private var shelfQuietUntil = 0L

    /** Cuts [data]'s pictures to size (from disk, or downloaded when [online]) and shows it all at once. */
    private suspend fun present(data: ShelfData, online: Boolean) {
        val sizes = posterSizes()
        val pictures = withContext(Dispatchers.IO) {
            data.items.mapIndexed { i, item -> shelfSource.picture(item, sizes[i].first, sizes[i].second, online) }
        }
        val colors = withContext(Dispatchers.Default) { pictures.map { VividColor.of(it) } }
        shelfData = data
        shelfComplete = pictures.all { it != null }
        showShelf(data, pictures, colors)
        if (online) {
            val keys = data.items.mapIndexed { i, item -> Images.keyFor(item.image, sizes[i].first, sizes[i].second) }.toSet()
            withContext(Dispatchers.IO) { shelfSource.prunePictures(keys) }
        }
    }

    // ---- The app dock -----------------------------------------------------------------------------------

    private var installedPackages: Set<String> = emptySet()
    private var appsJob: Job? = null

    /** Shows [apps] in the dock with their [covers] (by package) and the covers' vivid [colors]. */
    fun showApps(apps: List<DockApp>, covers: Map<String, Bitmap?> = emptyMap(), colors: Map<String, Int> = emptyMap()) {
        installedPackages = apps.map { it.packageName }.toSet()
        dock.setApps(apps)
        for (tile in dock.tiles) {
            covers[tile.app.packageName]?.let { tile.setArt(it) }
            colors[tile.app.packageName]?.let {
                tile.glowColor = it
                if (tile.isFocused) glow.show(it)
            }
        }
    }

    /** The covers' size for [count] apps in the dock slot (px). */
    fun coverSize(count: Int): Pair<Int, Int> = dock.tileSize(dockSlot.layoutParams.width, count)

    // A new order from the phone (the bridge runs in this process): the dock re-lays itself at once.
    private val dockOrderListener: () -> Unit = { loadApps() }

    private fun loadApps() {
        val pm = context.packageManager
        val apps = try {
            // The saved order is one short preference, read once per process and then kept in memory.
            DockApps.order(DockApps.installed(pm), DockOrder.load(context))
        } catch (e: RuntimeException) {
            // The package manager died with the system: keep the dock as it is.
            return
        }
        showApps(apps)
        appsJob?.cancel()
        appsJob = scope.launch {
            val (w, h) = coverSize(apps.size)
            val arts = withContext(Dispatchers.IO) { apps.map { cover(it, w, h) } }
            showApps(apps, apps.indices.associate { apps[it].packageName to arts[it].first }, apps.indices.associate { apps[it].packageName to arts[it].second })
        }
    }

    /** An app's cover (ours for the pinned apps, its own banner otherwise) cut to the tile, and its colour. */
    private fun cover(app: DockApp, width: Int, height: Int): Pair<Bitmap?, Int> {
        val key = "cover:${app.packageName}:${width}x$height"
        val bitmap = Images.remembered(key) ?: try {
            if (app.cover != 0) Images.decodeResource(context.resources, app.cover, width, height, opaque = true)
            else banner(app.packageName, width, height)
        } catch (e: RuntimeException) {
            null
        }?.also { Images.remember(key, it) }
        return bitmap to VividColor.of(bitmap)
    }

    private fun banner(packageName: String, width: Int, height: Int): Bitmap? {
        val pm = context.packageManager
        return try {
            val component = pm.getLeanbackLaunchIntentForPackage(packageName)?.component
            val banner = component?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(packageName)
            if (banner != null) Images.render(banner, width, height) else iconTile(pm.getApplicationIcon(packageName), width, height)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** An app with no TV banner: its icon in the middle of a dark tile. */
    private fun iconTile(icon: Drawable, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Tokens.FILL)
        val side = (height * 0.56f).roundToInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        icon.setBounds(left, top, left + side, top + side)
        icon.draw(canvas)
        return out
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    // ---- Keeping fresh ----------------------------------------------------------------------------------

    private val connectivity: ConnectivityManager? = if (live) context.getSystemService(ConnectivityManager::class.java) else null

    private fun online(): Boolean = connectivity?.activeNetwork != null

    /** The network coming up (at boot it lags the home screen) brings anything stale or missing. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (resumed) {
                    loadWeather()
                    loadShelf()
                }
            }
        }
    }

    private val refresher = object : Runnable {
        override fun run() {
            loadWeather()
            loadShelf()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    // Last, so every part above exists before it runs.
    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.findViewById<FrameLayout>(R.id.glowSlot).addView(glow, FrameLayout.LayoutParams(match, match))
        root.findViewById<FrameLayout>(R.id.bentoSlot).addView(bento, FrameLayout.LayoutParams(match, match))
        // The shelf reaches a ring's width past its slot on three sides, so focus rings stay inside it.
        val o = shelf.overhang
        val slot = shelfSlot.layoutParams
        shelfSlot.addView(shelf, FrameLayout.LayoutParams(slot.width + 2 * o, slot.height + o).apply {
            leftMargin = -o
            topMargin = -o
        })
        dockSlot.addView(dock, FrameLayout.LayoutParams(match, match))
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.findViewById<FrameLayout>(R.id.toastSlot).addView(toast, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dpi(Tokens.S5)
        })
        dock.onOpen = { host.launchApp(it.packageName) }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        NowPlayingHub.addListener(nowPlayingListener)
        showShelf(null, emptyList(), emptyList())
        updateClock()
    }

    private companion object {
        /** How often a resumed home screen checks whether its data is due (each source has its own age). */
        const val CHECK_MS = 5 * 60 * 1000L
        const val RETRY_MS = 60 * 1000L

        /** How long the shelf leaves its service alone after an answer that asking again won't improve. */
        const val QUIET_MS = 30 * 60 * 1000L
    }
}
