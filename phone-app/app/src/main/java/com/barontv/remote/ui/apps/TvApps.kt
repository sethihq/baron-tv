package com.barontv.remote.ui.apps

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.barontv.remote.R
import com.barontv.remote.tv.TvApp
import com.barontv.remote.tv.TvBridge

internal const val NETFLIX = "com.netflix.ninja"

/**
 * The covers the app ships (the same art as the TV's dock tiles), by package. Anything else gets a calm
 * tile with its name instead.
 */
@DrawableRes
internal fun coverOf(packageName: String): Int? = when (packageName) {
    "com.amazon.amazonvideo.livingroom" -> R.drawable.cover_primevideo
    NETFLIX -> R.drawable.cover_netflix
    "com.google.android.youtube.tv" -> R.drawable.cover_youtube
    "in.startv.hotstar" -> R.drawable.cover_jiohotstar
    "com.spotify.tv.android" -> R.drawable.cover_spotify
    "tv.cloudwalker.market" -> R.drawable.cover_appstore
    "com.cvte.tv.media" -> R.drawable.cover_media
    else -> null
}

/**
 * The prototype's seven, in the dock order the user set: what the Apps tab shows until the TV's new home
 * has listed its own apps (no bridge, or no TV yet). Netflix is among them, marked as not on this TV: it
 * can't run there (CLAUDE.md, "Netflix").
 */
internal val PrototypeApps: List<TvApp> = listOf(
    TvApp("Prime Video", "com.amazon.amazonvideo.livingroom", pinned = true),
    TvApp("Netflix", NETFLIX, pinned = true),
    TvApp("YouTube", "com.google.android.youtube.tv", pinned = true),
    TvApp("JioHotstar", "in.startv.hotstar", pinned = true),
    TvApp("Spotify", "com.spotify.tv.android", pinned = true),
    TvApp("App Store", "tv.cloudwalker.market", pinned = true),
    TvApp("Media Center", "com.cvte.tv.media", pinned = true),
)

/** A move in the dock that the phone has shown and the TV has yet to take: [moved] went to a new place. */
internal class DockMove(val moved: TvApp, val order: List<String>, val before: List<TvApp>, val after: List<TvApp>)

/**
 * What the TV's new home last said about its apps, kept by the app's shell so switching tabs never
 * flashes an empty grid. The Apps tab and the TV tab's dock list both read it, so a new dock order shows
 * in the Apps grid at once.
 */
@Stable
internal class TvApps(private val bridge: TvBridge, initial: List<TvApp>? = null) {
    /** The TV's apps in its dock order, pinned first; null until the TV has listed them once. */
    var list: List<TvApp>? by mutableStateOf(initial)
        private set

    // Dock orders on their way to the TV. A list read meanwhile may predate them, so it waits its turn.
    private var sending = 0

    /** Asks the TV again. An empty answer (no bridge, or no TV) keeps what it said last. */
    suspend fun refresh() {
        val apps = bridge.apps()
        if (apps.isNotEmpty() && sending == 0) list = apps
    }

    /**
     * Moves the dock app [packageName] to place [to] (counted among the pinned apps) on the phone straight
     * away, so the Apps grid and the dock list follow in the same frame, and returns the move to [send].
     * Null when there's nothing to move.
     */
    fun moveInDock(packageName: String, to: Int): DockMove? {
        val before = list ?: return null
        val pinned = before.filter { it.pinned }
        val from = pinned.indexOfFirst { it.packageName == packageName }
        if (from < 0 || from == to || to !in pinned.indices) return null
        val moved = pinned.toMutableList().apply { add(to, removeAt(from)) }
        val after = moved + before.filterNot { it.pinned }
        list = after
        return DockMove(pinned[from], moved.map { it.packageName }, before, after)
    }

    /** Tells the TV about [move]. If it doesn't take it, the order before it comes back. */
    suspend fun send(move: DockMove): Boolean {
        sending++
        val saved = try {
            bridge.setDock(move.order)
        } finally {
            sending--
        }
        // Only undo our own move: a later one may already have replaced it.
        if (!saved && list === move.after) list = move.before
        return saved
    }
}

/** "first" to "tenth", then 11th, 22nd and so on: where an app now sits in the dock, for the notice. */
internal fun ordinal(n: Int): String = when (n) {
    1 -> "first"
    2 -> "second"
    3 -> "third"
    4 -> "fourth"
    5 -> "fifth"
    6 -> "sixth"
    7 -> "seventh"
    8 -> "eighth"
    9 -> "ninth"
    10 -> "tenth"
    else -> {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        "$n$suffix"
    }
}
