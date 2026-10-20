package com.barontv.home.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.barontv.home.R
import java.util.concurrent.CopyOnWriteArrayList

/** An app in the dock: its package, its name, and the cover drawn for it ([cover] 0 for its own banner). */
data class DockApp(val packageName: String, val label: String, val cover: Int)

/**
 * Which apps the dock holds, in the user's order: Prime Video, Netflix, YouTube, JioHotstar, Spotify, App
 * Store, Media Center, each with its cover; any that isn't installed is skipped (Netflix can't run on this
 * TV). Then any other app with a TV launcher entry, A to Z, with its own banner, except the TV's own helper
 * screens and settings, and home screens.
 */
object DockApps {
    val PINNED = listOf(
        DockApp("com.amazon.amazonvideo.livingroom", "Prime Video", R.drawable.cover_primevideo),
        DockApp("com.netflix.ninja", "Netflix", R.drawable.cover_netflix),
        DockApp("com.google.android.youtube.tv", "YouTube", R.drawable.cover_youtube),
        DockApp("in.startv.hotstar", "JioHotstar", R.drawable.cover_jiohotstar),
        DockApp("com.spotify.tv.android", "Spotify", R.drawable.cover_spotify),
        DockApp("tv.cloudwalker.market", "App Store", R.drawable.cover_appstore),
        DockApp("com.cvte.tv.media", "Media Center", R.drawable.cover_media),
    )

    /** Focus starts here, as the prototype does. */
    const val FIRST_FOCUS = "com.google.android.youtube.tv"

    private val HIDDEN = setOf(
        "com.gathin.tvhome", // the old launcher
        "com.barontv.home",
        "com.cvte.settings", // the TV's settings apps: the side dock opens them
        "com.cvte.tv.setting",
        "com.android.tv.settings",
        "com.android.settings",
    )

    /** CloudWalker's helper screens (All Apps, search, the stock launcher...) never get a tile. */
    fun hidden(packageName: String): Boolean = packageName.startsWith("tv.cloudwalker.") || packageName in HIDDEN

    private val PINNED_NAMES = PINNED.map { it.packageName }.toSet()

    /** One of the user's own dock apps, drawn with its cover (the rest show their own banners). */
    fun isPinned(packageName: String): Boolean = packageName in PINNED_NAMES

    /**
     * The dock's order from what's installed: [found] is every app with a TV launcher entry as
     * (package, name). [saved] is the order the user set from the phone (DockOrder): those apps lead, in
     * that order, and the rest follow in the usual order; a saved app that's gone or hidden is skipped.
     * Pure, so it's tested without a TV.
     */
    fun order(found: List<Pair<String, String>>, saved: List<String> = emptyList()): List<DockApp> {
        val installed = found.associate { it }
        val pinned = PINNED.filter { it.packageName in installed }
        val others = found
            .filter { (pkg, _) -> pkg !in PINNED_NAMES && !hidden(pkg) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> DockApp(pkg, label, 0) }
        val usual = pinned + others
        if (saved.isEmpty()) return usual
        val byPackage = usual.associateBy { it.packageName }
        val first = saved.distinct().mapNotNull { byPackage[it] }
        val firstNames = first.map { it.packageName }.toSet()
        return first + usual.filter { it.packageName !in firstNames }
    }

    /**
     * What's installed with a TV launcher entry, plus pinned apps that only have a phone-style one.
     * Package visibility (Android 11 and later) doesn't apply: this only runs on the Baron TV's Android 9,
     * where every app is visible to a home screen.
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun installed(pm: PackageManager): List<Pair<String, String>> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(probe, 0).mapNotNull { ri ->
            val info = ri.activityInfo ?: return@mapNotNull null
            val pinned = PINNED.firstOrNull { it.packageName == info.packageName }
            // A pinned app's name is ours; anyone else's comes from the app (it loads its resources).
            info.packageName to (pinned?.label ?: ri.loadLabel(pm).toString())
        }.toMutableList()
        for (pin in PINNED) {
            if (found.none { it.first == pin.packageName } && pm.getLaunchIntentForPackage(pin.packageName) != null) {
                found += pin.packageName to pin.label
            }
        }
        return found
    }
}

/**
 * The dock order the user set from the phone (the bridge's /dock?order=), kept in the app's preferences
 * where the home screen reads it. The bridge and the home screen share one process, so a new order is
 * passed straight on and the dock re-lays itself at once. The bridge saves off the main thread; listeners
 * hear about it on the main thread.
 */
object DockOrder {
    private const val PREFS = "dock"
    private const val KEY = "order"

    // Made on first use: the order's sums are tested on the JVM, where there's no main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The saved order, first to last; empty means the usual order. */
    fun load(context: Context): List<String> = load(prefs(context))

    fun load(prefs: SharedPreferences): List<String> = decode(prefs.getString(KEY, null))

    /** Saves [order] (empty goes back to the usual order) and tells the home screen; false if it couldn't be written. */
    fun save(context: Context, order: List<String>): Boolean {
        val ok = save(prefs(context), order)
        if (ok) main.post { for (l in listeners) l() }
        return ok
    }

    /** Written at once (commit, not apply), so the phone's "ok" means it's on disk. */
    fun save(prefs: SharedPreferences, order: List<String>): Boolean {
        val clean = decode(encode(order))
        val edit = prefs.edit()
        if (clean.isEmpty()) edit.remove(KEY) else edit.putString(KEY, encode(clean))
        return edit.commit()
    }

    /** Package names never hold a comma, so the order is kept as one line. */
    fun encode(order: List<String>): String = order.joinToString(",") { it.trim() }

    fun decode(saved: String?): List<String> =
        saved?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** [listener] runs on the main thread whenever a new order is saved. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
