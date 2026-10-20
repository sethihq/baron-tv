package com.barontv.home.bridge

import com.barontv.home.volume.VolumeLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every endpoint of home/BRIDGE.md, its reply's status, type and JSON shape, against a stand-in TV. */
class RoutesTest {

    private class FakeTv : BridgeTv {
        val calls = ArrayList<String>()
        var level = VolumeLevel(35, false)
        var nowPlaying: BridgeNow? = null
        var dockOrder = listOf("com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "com.spotify.tv.android")

        override fun wake() {
            calls += "wake"
        }

        override fun openInputs() {
            calls += "openInputs"
        }

        override fun volume() = level

        override fun stepVolume(step: VolumeStep): VolumeLevel {
            calls += "step $step"
            level = when (step) {
                VolumeStep.UP -> level.copy(percent = level.percent + 5)
                VolumeStep.DOWN -> level.copy(percent = level.percent - 5)
                VolumeStep.MUTE -> level.copy(muted = !level.muted)
            }
            return level
        }

        override fun setVolume(percent: Int): VolumeLevel {
            calls += "set $percent"
            level = VolumeLevel(percent, false)
            return level
        }

        override fun inputs() = listOf(BridgeInput("HDMI 1", true), BridgeInput("HDMI 2", false), BridgeInput("AV", false))

        override fun switchInput(name: String): Boolean {
            calls += "source $name"
            return inputs().any { it.name == name }
        }

        override fun apps() = listOf(
            BridgeApp("Prime Video", "com.amazon.amazonvideo.livingroom", true),
            BridgeApp("YouTube", "com.google.android.youtube.tv", true),
            BridgeApp("VLC", "org.videolan.vlc", false),
        )

        override fun launch(packageName: String): Boolean {
            calls += "launch $packageName"
            return packageName != "com.example.missing"
        }

        override fun dock() = dockOrder

        override fun saveDock(order: List<String>): Boolean {
            calls += "dock ${order.joinToString(",")}"
            dockOrder = order
            return true
        }

        override fun now() = nowPlaying

        override fun media(action: MediaAction): Boolean {
            calls += "media $action"
            return true
        }

        override fun shortcut(shortcut: Shortcut): Boolean {
            calls += "shortcut $shortcut"
            return true
        }
    }

    private val tv = FakeTv()
    private val routes = Routes(tv)

    private fun get(target: String): Reply = routes.answer(Request.parse("GET $target HTTP/1.1")!!)

    private fun json(target: String): JSONObject {
        val reply = get(target)
        assertEquals(200, reply.status)
        assertEquals(Reply.JSON, reply.type)
        return JSONObject(reply.body)
    }

    private fun array(target: String): JSONArray {
        val reply = get(target)
        assertEquals(200, reply.status)
        assertEquals(Reply.JSON, reply.type)
        return JSONArray(reply.body)
    }

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    @Test
    fun theRootSaysTheBridgeIsThere() {
        val reply = get("/")
        assertEquals(200, reply.status)
        assertEquals(Reply.TEXT, reply.type)
        assertEquals("Baron Home", reply.body)
    }

    @Test
    fun wakeAndInputAnswerOk() {
        assertEquals("OK", get("/wake").body)
        assertEquals("OK", get("/input").body)
        assertEquals(listOf("wake", "openInputs"), tv.calls)
    }

    @Test
    fun volumeReadsTheLevel() {
        val v = json("/volume")
        assertEquals(setOf("level", "muted"), keys(v))
        assertEquals(35, v.getInt("level"))
        assertFalse(v.getBoolean("muted"))
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun volumeStepsAndAnswersTheLevelAfter() {
        assertEquals(40, json("/volume?step=up").getInt("level"))
        assertEquals(35, json("/volume?step=down").getInt("level"))
        val muted = json("/volume?step=mute")
        assertEquals(35, muted.getInt("level"))
        assertTrue(muted.getBoolean("muted"))
        assertEquals(listOf("step UP", "step DOWN", "step MUTE"), tv.calls)
    }

    @Test
    fun volumeSetsTheSlidersLevelKeptTo0To100() {
        assertEquals(40, json("/volume?set=40").getInt("level"))
        assertEquals(41, json("/volume?set=40.6").getInt("level"))
        assertEquals(100, json("/volume?set=250").getInt("level"))
        assertEquals(0, json("/volume?set=-3").getInt("level"))
        assertEquals(listOf("set 40", "set 41", "set 100", "set 0"), tv.calls)
    }

    @Test
    fun aVolumeThatMakesNoSenseIsLeftAlone() {
        assertEquals(35, json("/volume?step=sideways").getInt("level"))
        assertEquals(35, json("/volume?set=loud").getInt("level"))
        assertEquals(35, json("/volume?set=NaN").getInt("level"))
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun inputsListNameAndLive() {
        val list = array("/inputs")
        assertEquals(3, list.length())
        assertEquals(setOf("name", "live"), keys(list.getJSONObject(0)))
        assertEquals("HDMI 1", list.getJSONObject(0).getString("name"))
        assertTrue(list.getJSONObject(0).getBoolean("live"))
        assertFalse(list.getJSONObject(2).getBoolean("live"))
    }

    @Test
    fun sourceSwitchesByTheDecodedName() {
        assertTrue(json("/source?name=HDMI%201").getBoolean("ok"))
        assertEquals(listOf("source HDMI 1"), tv.calls)
        assertFalse(json("/source?name=HDMI%209").getBoolean("ok"))
        // No name: nothing is asked of the TV.
        assertFalse(json("/source").getBoolean("ok"))
        assertFalse(json("/source?name=").getBoolean("ok"))
        assertEquals(2, tv.calls.size)
    }

    @Test
    fun appsListNamePackageAndPinnedInOrder() {
        val list = array("/apps")
        assertEquals(setOf("name", "package", "pinned"), keys(list.getJSONObject(0)))
        assertEquals(listOf("Prime Video", "YouTube", "VLC"), (0 until list.length()).map { list.getJSONObject(it).getString("name") })
        assertEquals("com.google.android.youtube.tv", list.getJSONObject(1).getString("package"))
        assertTrue(list.getJSONObject(1).getBoolean("pinned"))
        assertFalse(list.getJSONObject(2).getBoolean("pinned"))
    }

    @Test
    fun launchOpensThePackage() {
        assertTrue(json("/launch?package=com.spotify.tv.android").getBoolean("ok"))
        assertFalse(json("/launch?package=com.example.missing").getBoolean("ok"))
        assertFalse(json("/launch").getBoolean("ok"))
        assertEquals(listOf("launch com.spotify.tv.android", "launch com.example.missing"), tv.calls)
    }

    @Test
    fun dockReadsTheOrder() {
        val list = array("/dock")
        assertEquals(tv.dockOrder, (0 until list.length()).map { list.getString(it) })
    }

    @Test
    fun dockSavesANewOrderDecodedAndCleaned() {
        assertTrue(json("/dock?order=com.spotify.tv.android,%20com.google.android.youtube.tv,,com.spotify.tv.android").getBoolean("ok"))
        assertEquals(listOf("dock com.spotify.tv.android,com.google.android.youtube.tv"), tv.calls)
        val list = array("/dock")
        assertEquals("com.spotify.tv.android", list.getString(0))
    }

    @Test
    fun anEmptyOrderGoesBackToTheUsualOne() {
        assertTrue(json("/dock?order=").getBoolean("ok"))
        assertEquals(listOf("dock "), tv.calls)
    }

    @Test
    fun nowIsEmptyWhenNothingPlays() {
        val reply = get("/now")
        assertEquals(Reply.JSON, reply.type)
        assertEquals("{}", reply.body)
    }

    @Test
    fun nowHasEveryFieldWhenSomethingPlays() {
        tv.nowPlaying = BridgeNow("com.spotify.tv.android", "Spotify", true, "Espresso", "Sabrina Carpenter", 62000, 175000)
        val now = json("/now")
        assertEquals(setOf("app", "appName", "playing", "title", "artist", "positionMs", "durationMs"), keys(now))
        assertEquals("com.spotify.tv.android", now.getString("app"))
        assertEquals("Spotify", now.getString("appName"))
        assertTrue(now.getBoolean("playing"))
        assertEquals("Espresso", now.getString("title"))
        assertEquals("Sabrina Carpenter", now.getString("artist"))
        assertEquals(62000L, now.getLong("positionMs"))
        assertEquals(175000L, now.getLong("durationMs"))
    }

    @Test
    fun nowUnreadIsEmptyStringsAndMinusOne() {
        tv.nowPlaying = BridgeNow("com.spotify.tv.android", "Spotify", true, "", "", -1, -1)
        val now = json("/now")
        assertEquals("", now.getString("title"))
        assertEquals("", now.getString("artist"))
        assertEquals(-1L, now.getLong("positionMs"))
        assertEquals(-1L, now.getLong("durationMs"))
    }

    @Test
    fun mediaSendsTheKey() {
        assertTrue(json("/media?action=playpause").getBoolean("ok"))
        assertTrue(json("/media?action=next").getBoolean("ok"))
        assertTrue(json("/media?action=previous").getBoolean("ok"))
        assertFalse(json("/media?action=rewind").getBoolean("ok"))
        assertFalse(json("/media").getBoolean("ok"))
        assertEquals(listOf("media PLAY_PAUSE", "media NEXT", "media PREVIOUS"), tv.calls)
        assertEquals(85, MediaAction.PLAY_PAUSE.keyCode)
        assertEquals(87, MediaAction.NEXT.keyCode)
        assertEquals(88, MediaAction.PREVIOUS.keyCode)
    }

    @Test
    fun shortcutsOpenPictureSoundAndSettings() {
        assertTrue(json("/shortcut?name=picture").getBoolean("ok"))
        assertTrue(json("/shortcut?name=sound").getBoolean("ok"))
        assertTrue(json("/shortcut?name=settings").getBoolean("ok"))
        assertFalse(json("/shortcut?name=wifi").getBoolean("ok"))
        assertEquals(listOf("shortcut PICTURE", "shortcut SOUND", "shortcut SETTINGS"), tv.calls)
    }

    @Test
    fun anUnknownPathIs404AndOnlyGetIsAnswered() {
        assertEquals(404, get("/nothing").status)
        assertEquals(404, get("/volume/up").status)
        assertEquals(405, routes.answer(Request.parse("POST /dock?order=a HTTP/1.1")!!).status)
        assertTrue(tv.calls.isEmpty())
    }
}
