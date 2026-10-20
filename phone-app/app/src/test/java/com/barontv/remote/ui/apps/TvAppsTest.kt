package com.barontv.remote.ui.apps

import com.barontv.remote.R
import com.barontv.remote.tv.TvApp
import com.barontv.remote.ui.FakeTvBridge
import com.barontv.remote.ui.Tab
import com.barontv.remote.ui.tvtab.TvInputs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Apps and TV tabs know about the TV's apps, and how a new dock order travels.
 *
 * runBlocking rather than runTest: runTest fails on any coroutine failure from an earlier test, and a
 * snapshot test of a screen using `repeatOnLifecycle` (or `collectAsStateWithLifecycle`) leaves one,
 * since Paparazzi has no `Dispatchers.Main`.
 */
class TvAppsTest {
    private val kodi = TvApp("Kodi", "org.xbmc.kodi", pinned = false)

    private fun packages(apps: List<TvApp>?) = apps.orEmpty().map { it.packageName }

    @Test
    fun noListUntilTheTvHasGivenOne() = runBlocking {
        val bridge = FakeTvBridge(answering = false)
        val apps = TvApps(bridge)
        apps.refresh()
        assertNull(apps.list)

        bridge.answer(true)
        apps.refresh()
        assertEquals(FakeTvBridge.TheTvsApps, apps.list)
    }

    @Test
    fun anEmptyAnswerKeepsTheLastList() = runBlocking {
        val bridge = FakeTvBridge()
        val apps = TvApps(bridge)
        apps.refresh()
        bridge.answer(false)
        apps.refresh()
        assertEquals("the TV going quiet doesn't wipe what it said", FakeTvBridge.TheTvsApps, apps.list)
    }

    @Test
    fun aMoveShowsAtOnceThenReachesTheTv() = runBlocking {
        val bridge = FakeTvBridge(apps = FakeTvBridge.TheTvsApps + kodi)
        val apps = TvApps(bridge, bridge.apps)

        // YouTube (second) to the fourth place.
        val move = checkNotNull(apps.moveInDock("com.google.android.youtube.tv", 3))
        val expected = listOf(
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "com.spotify.tv.android",
            "com.google.android.youtube.tv",
            "tv.cloudwalker.market",
            "com.cvte.tv.media",
        )
        assertEquals("on the phone before the TV hears of it", expected + "org.xbmc.kodi", packages(apps.list))
        assertEquals("YouTube", move.moved.name)
        assertTrue(bridge.docks.isEmpty())

        assertTrue(apps.send(move))
        assertEquals("only the pinned apps go to the TV", listOf(expected), bridge.docks)
        assertEquals(expected + "org.xbmc.kodi", packages(apps.list))
    }

    @Test
    fun aMoveTheTvRefusesIsUndone() = runBlocking {
        val bridge = FakeTvBridge(failing = true)
        val apps = TvApps(bridge, bridge.apps)
        val move = checkNotNull(apps.moveInDock("com.amazon.amazonvideo.livingroom", 5))
        assertEquals("com.amazon.amazonvideo.livingroom", packages(apps.list).last())

        assertEquals(false, apps.send(move))
        assertEquals(FakeTvBridge.TheTvsApps, apps.list)
    }

    @Test
    fun aRefusedMoveDoesNotUndoALaterOne() = runBlocking {
        val bridge = FakeTvBridge(failing = true)
        val apps = TvApps(bridge, bridge.apps)
        val first = checkNotNull(apps.moveInDock("com.amazon.amazonvideo.livingroom", 1))
        val second = checkNotNull(apps.moveInDock("in.startv.hotstar", 0))
        apps.send(first)
        assertSame("the later order stays until its own answer", second.after, apps.list)
    }

    @Test
    fun nothingToMove() {
        val apps = TvApps(FakeTvBridge())
        assertNull("no list yet", apps.moveInDock("com.amazon.amazonvideo.livingroom", 1))
        val listed = TvApps(FakeTvBridge(), FakeTvBridge.TheTvsApps)
        assertNull("same place", listed.moveInDock("in.startv.hotstar", 2))
        assertNull("past the end", listed.moveInDock("com.amazon.amazonvideo.livingroom", 6))
        assertNull("not in the dock", listed.moveInDock("org.xbmc.kodi", 0))
    }

    @Test
    fun inputsKeepTheLastAnswerToo() = runBlocking {
        val bridge = FakeTvBridge()
        val inputs = TvInputs(bridge)
        assertNull(inputs.list)
        inputs.refresh()
        assertEquals(listOf("HDMI 1", "HDMI 2", "AV"), inputs.list.orEmpty().map { it.name })
        bridge.answer(false)
        inputs.refresh()
        assertEquals(3, inputs.list.orEmpty().size)
    }

    @Test
    fun theSevenStandInsAllHaveCovers() {
        assertEquals(7, PrototypeApps.size)
        assertTrue(PrototypeApps.all { coverOf(it.packageName) != null })
        assertEquals(R.drawable.cover_netflix, coverOf(NETFLIX))
        assertNull("anything else gets a calm tile", coverOf("org.xbmc.kodi"))
    }

    @Test
    fun ordinalsReadAsWords() {
        assertEquals("first", ordinal(1))
        assertEquals("seventh", ordinal(7))
        assertEquals("tenth", ordinal(10))
        assertEquals("11th", ordinal(11))
        assertEquals("12th", ordinal(12))
        assertEquals("21st", ordinal(21))
        assertEquals("22nd", ordinal(22))
        assertEquals("23rd", ordinal(23))
        assertEquals("111th", ordinal(111))
    }

    @Test
    fun theLastTabComesBackByName() {
        assertEquals(Tab.Apps, Tab.named("Apps"))
        assertEquals(Tab.Tv, Tab.named(Tab.Tv.name))
        assertEquals("a first launch opens the Remote", Tab.Remote, Tab.named(null))
        assertEquals("so does a tab that's gone", Tab.Remote, Tab.named("Lights"))
    }
}
