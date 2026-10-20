package com.barontv.home.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockAppsTest {
    /** What this TV lists with a TV launcher entry (no Netflix), plus helpers and home screens. */
    private val baron = listOf(
        "com.spotify.tv.android" to "Spotify",
        "tv.cloudwalker.market" to "App Store",
        "com.google.android.youtube.tv" to "YouTube",
        "com.cvte.tv.media" to "Media Center",
        "in.startv.hotstar" to "JioHotstar",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "tv.cloudwalker.launcher.com" to "Lite Launcher",
        "tv.cloudwalker.apps" to "All Apps",
        "com.gathin.tvhome" to "Home",
        "com.barontv.home" to "Baron Home",
        "com.cvte.tv.setting" to "TV Settings",
    )

    @Test
    fun theUsersOrderWithoutNetflix() {
        assertEquals(
            listOf("Prime Video", "YouTube", "JioHotstar", "Spotify", "App Store", "Media Center"),
            DockApps.order(baron).map { it.label },
        )
    }

    @Test
    fun otherAppsFollowAToZWithTheirOwnBanners() {
        val apps = DockApps.order(baron + listOf("org.videolan.vlc" to "VLC", "com.example.browser" to "browser", "org.videolan.vlc" to "VLC"))
        assertEquals(listOf("browser", "VLC"), apps.drop(6).map { it.label })
        assertTrue(apps.drop(6).all { it.cover == 0 })
    }

    @Test
    fun cloudWalkerHelpersAreHiddenButTheStoreStays() {
        assertTrue(DockApps.hidden("tv.cloudwalker.search"))
        assertTrue(DockApps.hidden("com.android.tv.settings"))
        assertFalse(DockApps.hidden("com.google.android.youtube.tv"))
        assertTrue(DockApps.order(baron).any { it.packageName == "tv.cloudwalker.market" })
    }

    @Test
    fun allSevenWhenNetflixIsThere() {
        val apps = DockApps.order(baron + ("com.netflix.ninja" to "Netflix"))
        assertEquals("Netflix", apps[1].label)
        assertEquals(7, apps.size)
    }
}
