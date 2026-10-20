package com.barontv.remote.tv

import com.barontv.remote.tv.FakeTvServer.Answer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge client against stand-ins on this machine: one for the home's bridge, answering as
 * home/BRIDGE.md says, and one for CloudWalker's server, answering OK to everything. The remote is a stub,
 * connected to 127.0.0.1, that notes the keys it's given.
 */
class HttpTvBridgeTest {
    private val opened = mutableListOf<Closeable>()
    private val bridges = mutableListOf<HttpTvBridge>()
    private val cloudWalker = FakeTvServer().closedAfter()
    private val remote = StubRemote()

    @After
    fun tearDown() {
        bridges.forEach { it.stop() }
        opened.forEach { it.close() }
    }

    private fun <T : Closeable> T.closedAfter(): T = also { opened += it }

    private fun bridgeAt(port: Int, timings: BridgeTimings = BridgeTimings()) = HttpTvBridge(
        remote = remote,
        connect = { it.openConnection(Proxy.NO_PROXY) },
        io = Dispatchers.IO,
        bridgePort = port,
        remotePort = cloudWalker.port,
        timings = timings,
        log = ::println,
    ).also { bridges += it }

    /** A started client that has found the bridge on [port]. */
    private suspend fun foundAt(port: Int, timings: BridgeTimings = BridgeTimings()): HttpTvBridge {
        val bridge = bridgeAt(port, timings)
        bridge.start()
        withTimeout(5_000) { bridge.available.first { it } }
        return bridge
    }

    /** The home's bridge as BRIDGE.md has it, at volume 35 with Spotify playing. */
    private fun homeBridge(nowPlaying: String = SPOTIFY) = FakeTvServer { path, query ->
        when (path) {
            "/" -> Answer(200, "Baron Home")
            "/volume" -> when {
                query == null -> volume(35)
                query == "step=up" -> volume(40)
                query == "step=down" -> volume(30)
                query == "step=mute" -> volume(35, muted = true)
                query.startsWith("set=") -> volume(query.removePrefix("set=").toInt())
                else -> Answer(400, "")
            }
            "/inputs" -> Answer(200, """[{"name":"HDMI 1","live":true},{"name":"HDMI 2","live":false},{"name":"AV","live":false}]""")
            "/apps" -> Answer(
                200,
                """[{"name":"YouTube","package":"com.google.android.youtube.tv","pinned":true},""" +
                    """{"name":"Spotify","package":"com.spotify.tv.android","pinned":false}]""",
            )
            "/now" -> Answer(200, nowPlaying)
            "/source", "/launch", "/dock", "/media", "/shortcut" -> Answer(200, """{"ok":true}""")
            else -> Answer(404, "Not found")
        }
    }.closedAfter()

    // Finding the bridge.

    @Test
    fun `the bridge is there once it says Baron Home`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = bridgeAt(tv.port)
        assertFalse(bridge.available.value)
        bridge.start()
        withTimeout(5_000) { bridge.available.first { it } }
        assertEquals(listOf("/"), tv.requests.toList())
    }

    @Test
    fun `something else on that port isn't the bridge`(): Unit = runBlocking {
        val other = FakeTvServer().closedAfter() // OK to everything, as CloudWalker's server says
        val bridge = bridgeAt(other.port)
        bridge.start()
        withTimeout(5_000) { while (other.requests.isEmpty()) delay(10) }
        delay(100)
        assertFalse(bridge.available.value)
        assertNull(bridge.volume())
        assertEquals("nothing but the check", listOf("/"), other.requests.toList())
    }

    @Test
    fun `with nothing on that port there's no bridge`(): Unit = runBlocking {
        val bridge = bridgeAt(freePort())
        bridge.start()
        delay(300)
        assertFalse(bridge.available.value)
    }

    @Test
    fun `the bridge is looked for again every so often`(): Unit = runBlocking {
        val up = AtomicBoolean(false)
        val tv = FakeTvServer { path, _ ->
            if (path == "/" && up.get()) Answer(200, "Baron Home") else Answer(404, "Not found")
        }.closedAfter()
        val bridge = bridgeAt(tv.port, BridgeTimings(checkEveryMs = 100))
        bridge.start()
        withTimeout(5_000) { while (tv.requests.isEmpty()) delay(10) }
        assertFalse(bridge.available.value)
        up.set(true) // the home installed, or its accessibility service turned on
        withTimeout(5_000) { bridge.available.first { it } }
        up.set(false)
        withTimeout(5_000) { bridge.available.first { !it } }
    }

    @Test
    fun `losing the TV loses the bridge, and connecting again checks at once`(): Unit = runBlocking {
        val tv = homeBridge()
        // Checks every 15 s, so only the new connection can explain a second check this soon.
        val bridge = foundAt(tv.port)
        remote.state.value = TvState.NotFound
        withTimeout(5_000) { bridge.available.first { !it } }
        remote.state.value = TvState.Connected("Baron TV", "127.0.0.1")
        withTimeout(5_000) { bridge.available.first { it } }
        assertEquals(listOf("/", "/"), tv.requests.toList())
    }

    @Test
    fun `stopped, it stops checking and keeps what it found`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port, BridgeTimings(checkEveryMs = 50))
        bridge.stop()
        delay(100) // a check already on its way may still land
        val checks = tv.requests.size
        delay(400)
        assertEquals(checks, tv.requests.size)
        assertTrue(bridge.available.value)
    }

    // Every request, with the bridge there.

    @Test
    fun `volume, its steps and the slider`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        assertEquals(Volume(35, muted = false), bridge.volume())
        assertEquals(Volume(40, muted = false), bridge.changeVolume(VolumeStep.UP))
        assertEquals(Volume(30, muted = false), bridge.changeVolume(VolumeStep.DOWN))
        assertEquals(Volume(35, muted = true), bridge.changeVolume(VolumeStep.MUTE))
        assertEquals(Volume(40, muted = false), bridge.setVolume(40))
        assertEquals("kept to 0 to 100", Volume(100, muted = false), bridge.setVolume(250))
        assertEquals(Volume(0, muted = false), bridge.setVolume(-5))
        assertEquals(
            listOf(
                "/", "/volume", "/volume?step=up", "/volume?step=down", "/volume?step=mute",
                "/volume?set=40", "/volume?set=100", "/volume?set=0",
            ),
            tv.requests.toList(),
        )
        assertEquals("no key presses while the bridge answers", emptyList<TvKey>(), remote.sent.toList())
    }

    @Test
    fun `inputs, and switching to one`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        assertEquals(
            listOf(TvInput("HDMI 1", live = true), TvInput("HDMI 2", live = false), TvInput("AV", live = false)),
            bridge.inputs(),
        )
        assertTrue(bridge.switchInput("HDMI 1"))
        assertEquals("/source?name=HDMI%201", tv.requests.last())
    }

    @Test
    fun `apps, opening one, and the dock order`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        assertEquals(
            listOf(
                TvApp("YouTube", "com.google.android.youtube.tv", pinned = true),
                TvApp("Spotify", "com.spotify.tv.android", pinned = false),
            ),
            bridge.apps(),
        )
        assertTrue(bridge.launch("com.spotify.tv.android"))
        assertEquals("/launch?package=com.spotify.tv.android", tv.requests.last())
        assertTrue(bridge.setDock(listOf("com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv")))
        assertEquals("/dock?order=com.amazon.amazonvideo.livingroom,com.google.android.youtube.tv", tv.requests.last())
        assertEquals("CloudWalker's server isn't needed", emptyList<String>(), cloudWalker.requests.toList())
    }

    @Test
    fun `what's playing, and the media keys`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        assertEquals(
            NowPlaying(
                packageName = "com.spotify.tv.android",
                appName = "Spotify",
                playing = true,
                title = "Blinding Lights",
                artist = "The Weeknd",
                positionMs = 61_000,
                durationMs = 200_000,
            ),
            bridge.nowPlaying(),
        )
        assertTrue(bridge.media(MediaAction.PLAY_PAUSE))
        assertEquals("/media?action=playpause", tv.requests.last())
        assertTrue(bridge.media(MediaAction.NEXT))
        assertEquals("/media?action=next", tv.requests.last())
        assertTrue(bridge.media(MediaAction.PREVIOUS))
        assertEquals("/media?action=previous", tv.requests.last())
    }

    @Test
    fun `nothing playing is null`(): Unit = runBlocking {
        val bridge = foundAt(homeBridge(nowPlaying = "{}").port)
        assertNull(bridge.nowPlaying())
    }

    @Test
    fun `what's playing when the TV can't read the song`(): Unit = runBlocking {
        val unread = """{"app":"com.spotify.tv.android","appName":"Spotify","playing":true,"title":"","artist":"",""" +
            """"positionMs":-1,"durationMs":-1}"""
        val bridge = foundAt(homeBridge(nowPlaying = unread).port)
        assertEquals(
            NowPlaying("com.spotify.tv.android", "Spotify", playing = true, title = "", artist = "", positionMs = -1, durationMs = -1),
            bridge.nowPlaying(),
        )
    }

    @Test
    fun `the TV's shortcuts`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        for ((shortcut, name) in listOf(Shortcut.PICTURE to "picture", Shortcut.SOUND to "sound", Shortcut.SETTINGS to "settings")) {
            assertTrue(bridge.shortcut(shortcut))
            assertEquals("/shortcut?name=$name", tv.requests.last())
        }
    }

    // Without the bridge.

    @Test
    fun `without a bridge, apps open through CloudWalker's server and volume is key presses`(): Unit = runBlocking {
        val bridge = bridgeAt(freePort())
        bridge.start()
        delay(200)
        assertFalse(bridge.available.value)
        assertTrue(bridge.launch("com.spotify.tv.android"))
        assertEquals(listOf("/play?packageName=com.spotify.tv.android"), cloudWalker.requests.toList())
        assertNull(bridge.changeVolume(VolumeStep.UP))
        assertNull(bridge.changeVolume(VolumeStep.DOWN))
        assertNull(bridge.changeVolume(VolumeStep.MUTE))
        assertEquals(listOf(TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, TvKey.MUTE), remote.sent.toList())
    }

    @Test
    fun `without a bridge, the rest is null, empty or false`(): Unit = runBlocking {
        val bridge = bridgeAt(freePort())
        bridge.start()
        delay(200)
        assertNull(bridge.volume())
        assertNull(bridge.setVolume(40))
        assertEquals(emptyList<TvInput>(), bridge.inputs())
        assertFalse(bridge.switchInput("HDMI 1"))
        assertEquals(emptyList<TvApp>(), bridge.apps())
        assertFalse(bridge.setDock(listOf("com.google.android.youtube.tv")))
        assertNull(bridge.nowPlaying())
        assertFalse(bridge.media(MediaAction.NEXT))
        assertFalse(bridge.shortcut(Shortcut.SETTINGS))
        assertEquals("nothing else falls back", emptyList<String>(), cloudWalker.requests.toList())
        assertEquals(emptyList<TvKey>(), remote.sent.toList())
    }

    @Test
    fun `with no TV, nothing is asked of anything`(): Unit = runBlocking {
        val tv = homeBridge()
        remote.state.value = TvState.NotFound
        val bridge = bridgeAt(tv.port)
        bridge.start()
        delay(100)
        assertFalse(bridge.available.value)
        assertFalse(bridge.launch("com.spotify.tv.android"))
        assertNull(bridge.changeVolume(VolumeStep.UP))
        assertEquals(emptyList<String>(), tv.requests.toList())
        assertEquals(emptyList<String>(), cloudWalker.requests.toList())
        assertEquals("the remote drops presses with no TV", emptyList<TvKey>(), remote.sent.toList())
    }

    @Test
    fun `an older bridge that doesn't know a request falls back as if there were none`(): Unit = runBlocking {
        // Today's bridge on the TV: only /, /wake and /input.
        val old = FakeTvServer { path, _ ->
            if (path == "/") Answer(200, "Baron Home") else Answer(404, "Not found")
        }.closedAfter()
        val bridge = foundAt(old.port)
        assertNull(bridge.changeVolume(VolumeStep.UP))
        assertEquals(listOf(TvKey.VOLUME_UP), remote.sent.toList())
        assertTrue(bridge.launch("com.spotify.tv.android"))
        assertEquals(listOf("/play?packageName=com.spotify.tv.android"), cloudWalker.requests.toList())
        assertEquals(emptyList<TvApp>(), bridge.apps())
        assertTrue("a 404 doesn't mean the bridge has gone", bridge.available.value)
    }

    @Test
    fun `a bridge that goes away is noticed at the next request, which falls back`(): Unit = runBlocking {
        val tv = homeBridge()
        val bridge = foundAt(tv.port)
        tv.close()
        assertNull(bridge.changeVolume(VolumeStep.UP))
        assertEquals(listOf(TvKey.VOLUME_UP), remote.sent.toList())
        assertFalse(bridge.available.value)
        assertTrue(bridge.launch("com.spotify.tv.android"))
        assertEquals(listOf("/play?packageName=com.spotify.tv.android"), cloudWalker.requests.toList())
    }

    @Test
    fun `a bridge that answers badly isn't asked another way`(): Unit = runBlocking {
        val broken = FakeTvServer { path, _ ->
            when (path) {
                "/" -> Answer(200, "Baron Home")
                "/volume" -> Answer(500, "Something broke")
                "/launch" -> Answer(200, "not json")
                "/apps" -> Answer(200, """{"not":"a list"}""")
                "/now" -> Answer(200, "[]")
                else -> Answer(200, "")
            }
        }.closedAfter()
        val bridge = foundAt(broken.port)
        assertNull(bridge.changeVolume(VolumeStep.UP))
        assertFalse(bridge.launch("com.spotify.tv.android"))
        assertEquals("it may have happened: no key press", emptyList<TvKey>(), remote.sent.toList())
        assertEquals("and no second way of opening it", emptyList<String>(), cloudWalker.requests.toList())
        assertEquals(emptyList<TvApp>(), bridge.apps())
        assertNull(bridge.nowPlaying())
        assertFalse(bridge.shortcut(Shortcut.PICTURE))
        assertTrue(bridge.available.value)
    }

    @Test
    fun `a bridge that never answers times out, and isn't asked another way`(): Unit = runBlocking {
        val hung = HungBridge().closedAfter()
        val bridge = foundAt(hung.port, BridgeTimings(timeoutMs = 300))
        val took = measureTimeMillis { assertNull(bridge.changeVolume(VolumeStep.UP)) }
        assertTrue("took $took ms", took in 250..2_000)
        assertEquals(emptyList<TvKey>(), remote.sent.toList())
    }

    @Test
    fun `a cancelled request lets go at once`(): Unit = runBlocking {
        val hung = HungBridge().closedAfter()
        val bridge = foundAt(hung.port, BridgeTimings(timeoutMs = 10_000))
        val call = launch(Dispatchers.Default) { bridge.apps() }
        delay(200)
        val took = measureTimeMillis { call.cancelAndJoin() }
        assertTrue("took $took ms", took < 1_000)
    }

    // Reading the replies.

    @Test
    fun `replies that don't fit read as no answer, and missing fields as empty`() {
        assertNull(volumeOf("OK"))
        assertNull(volumeOf("{}"))
        assertNull(volumeOf("""{"level":"loud"}"""))
        assertEquals(Volume(100, muted = false), volumeOf("""{"level":140}"""))
        assertEquals(emptyList<TvInput>(), inputsOf("{}"))
        assertEquals(listOf(TvInput("AV", live = false)), inputsOf("""[{"name":"AV"},{"live":true},null,7,{"name":null}]"""))
        assertEquals(listOf(TvApp("", "a.b", pinned = false)), appsOf("""[{"package":"a.b"},{"name":"No package"}]"""))
        assertNull(nowPlayingOf("{}"))
        assertNull(nowPlayingOf("[]"))
        assertEquals(
            NowPlaying("a.b", "", playing = false, title = "", artist = "", positionMs = -1, durationMs = -1),
            nowPlayingOf("""{"app":"a.b","title":null}"""),
        )
        assertTrue(okOf("""{"ok":true}"""))
        assertFalse(okOf("""{"ok":false}"""))
        assertFalse(okOf("{}"))
        assertFalse(okOf("OK"))
        assertFalse(okOf(""))
    }

    private companion object {
        val SPOTIFY = """{"app":"com.spotify.tv.android","appName":"Spotify","playing":true,""" +
            """"title":"Blinding Lights","artist":"The Weeknd","positionMs":61000,"durationMs":200000}"""

        fun volume(level: Int, muted: Boolean = false) = Answer(200, """{"level":$level,"muted":$muted}""")

        /** A port with nothing listening on it. */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

/** A remote connected to this machine that notes the keys it's given, dropping them with no TV as the real one does. */
private class StubRemote : TvRemote {
    override val state = MutableStateFlow<TvState>(TvState.Connected("Baron TV", "127.0.0.1"))
    val sent: MutableList<TvKey> = Collections.synchronizedList(mutableListOf())

    override fun send(key: TvKey) {
        if (state.value is TvState.Connected) sent += key
    }

    override fun retry() {}
}

/**
 * A bridge that answers the check and then hangs: every other request is taken and never answered, as by
 * a TV that has stopped responding mid-request.
 */
private class HungBridge : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val held: MutableList<Socket> = Collections.synchronizedList(mutableListOf())
    val port: Int = server.localPort

    init {
        Thread {
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                val request = client.getInputStream().bufferedReader().readLine().orEmpty()
                if (request.startsWith("GET / ")) {
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nBaron Home".toByteArray(),
                    )
                    client.close()
                } else {
                    held += client
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
        held.forEach { it.close() }
    }
}
