package com.barontv.home.home

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import com.barontv.home.R

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

    /**
     * The dock's order from what's installed: [found] is every app with a TV launcher entry as
     * (package, name). Pure, so it's tested without a TV.
     */
    fun order(found: List<Pair<String, String>>): List<DockApp> {
        val installed = found.associate { it }
        val pinned = PINNED.filter { it.packageName in installed }
        val pinnedNames = PINNED.map { it.packageName }.toSet()
        val others = found
            .filter { (pkg, _) -> pkg !in pinnedNames && !hidden(pkg) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> DockApp(pkg, label, 0) }
        return pinned + others
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
