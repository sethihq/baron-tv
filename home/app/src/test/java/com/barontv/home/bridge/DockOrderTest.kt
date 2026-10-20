package com.barontv.home.bridge

import android.content.SharedPreferences
import com.barontv.home.home.DockApps
import com.barontv.home.home.DockOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock order the phone saves (/dock?order=) and the home screen reads. */
class DockOrderTest {

    /** SharedPreferences in memory, enough for strings. */
    private class Prefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        var commits = 0

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += map.keys }
            override fun commit(): Boolean {
                for (k in removes) map.remove(k)
                map.putAll(puts)
                commits++
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    /** What this TV lists with a TV launcher entry (no Netflix), plus a helper and an app of its own. */
    private val baron = listOf(
        "com.spotify.tv.android" to "Spotify",
        "tv.cloudwalker.market" to "App Store",
        "com.google.android.youtube.tv" to "YouTube",
        "com.cvte.tv.media" to "Media Center",
        "in.startv.hotstar" to "JioHotstar",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "tv.cloudwalker.apps" to "All Apps",
        "com.barontv.home" to "Baron Home",
        "org.videolan.vlc" to "VLC",
    )

    private fun labels(saved: List<String>) = DockApps.order(baron, saved).map { it.label }

    @Test
    fun savesAndLoadsTheOrder() {
        val prefs = Prefs()
        assertTrue(DockOrder.save(prefs, listOf("com.spotify.tv.android", "com.google.android.youtube.tv")))
        assertEquals(1, prefs.commits)
        assertEquals(listOf("com.spotify.tv.android", "com.google.android.youtube.tv"), DockOrder.load(prefs))
    }

    @Test
    fun nothingSavedIsTheUsualOrder() {
        assertEquals(emptyList<String>(), DockOrder.load(Prefs()))
    }

    @Test
    fun anEmptyOrderGoesBackToTheUsualOne() {
        val prefs = Prefs()
        DockOrder.save(prefs, listOf("com.spotify.tv.android"))
        assertTrue(DockOrder.save(prefs, emptyList()))
        assertFalse(prefs.contains("order"))
        assertEquals(emptyList<String>(), DockOrder.load(prefs))
    }

    @Test
    fun theSavedLineIsCleaned() {
        val prefs = Prefs()
        DockOrder.save(prefs, listOf(" a.b ", "", "c.d", "a.b"))
        assertEquals("a.b,c.d", prefs.map["order"])
        assertEquals(listOf("a.b", "c.d"), DockOrder.decode(" a.b ,, c.d,a.b"))
        assertEquals(emptyList<String>(), DockOrder.decode(null))
        assertEquals(emptyList<String>(), DockOrder.decode(""))
    }

    @Test
    fun theUsualOrderWithoutASavedOne() {
        assertEquals(listOf("Prime Video", "YouTube", "JioHotstar", "Spotify", "App Store", "Media Center", "VLC"), labels(emptyList()))
    }

    @Test
    fun savedAppsLeadAndTheRestFollowInTheUsualOrder() {
        assertEquals(
            listOf("Spotify", "VLC", "Prime Video", "YouTube", "JioHotstar", "App Store", "Media Center"),
            labels(listOf("com.spotify.tv.android", "org.videolan.vlc")),
        )
    }

    @Test
    fun aWholeOrderIsKeptAsItIs() {
        val whole = listOf(
            "com.google.android.youtube.tv", "com.spotify.tv.android", "org.videolan.vlc", "in.startv.hotstar",
            "com.amazon.amazonvideo.livingroom", "com.cvte.tv.media", "tv.cloudwalker.market",
        )
        assertEquals(whole, DockApps.order(baron, whole).map { it.packageName })
    }

    @Test
    fun goneHiddenAndRepeatedAppsInTheSavedOrderAreSkipped() {
        val apps = DockApps.order(
            baron,
            listOf("com.netflix.ninja", "tv.cloudwalker.apps", "com.barontv.home", "com.spotify.tv.android", "com.spotify.tv.android"),
        )
        assertEquals("Spotify", apps.first().label)
        assertEquals(7, apps.size)
        assertFalse(apps.any { it.packageName == "tv.cloudwalker.apps" || it.packageName == "com.barontv.home" })
    }

    @Test
    fun pinnedMeansOneOfTheUsersOwnDockApps() {
        assertTrue(DockApps.isPinned("com.spotify.tv.android"))
        assertTrue(DockApps.isPinned("com.netflix.ninja"))
        assertFalse(DockApps.isPinned("org.videolan.vlc"))
        // Moving an app first doesn't make it pinned: pinned is about its cover, not its place.
        assertFalse(DockApps.order(baron, listOf("org.videolan.vlc")).first().let { DockApps.isPinned(it.packageName) })
    }
}
