package com.barontv.home.input

import com.barontv.home.bridge.BridgeApp
import com.barontv.home.bridge.BridgeInput
import com.barontv.home.bridge.BridgeNow
import com.barontv.home.bridge.BridgeTv
import com.barontv.home.bridge.MediaAction
import com.barontv.home.bridge.Routes
import com.barontv.home.bridge.Shortcut
import com.barontv.home.bridge.VolumeStep
import com.barontv.home.volume.VolumeLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/** The bridge's server end to end, over a socket on this machine: the HTTP around Routes' answers. */
class RemoteBridgeTest {

    private val sources: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val tv = object : BridgeTv {
        override fun wake() {}
        override fun openInputs() {}
        override fun volume() = VolumeLevel(35, false)
        override fun stepVolume(step: VolumeStep) = VolumeLevel(if (step == VolumeStep.UP) 40 else 30, false)
        override fun setVolume(percent: Int) = VolumeLevel(percent, false)
        override fun inputs() = listOf(BridgeInput("HDMI 1", true), BridgeInput("Café TV", false))
        override fun switchInput(name: String): Boolean {
            sources += name
            return true
        }
        override fun apps() = emptyList<BridgeApp>()
        override fun launch(packageName: String) = true
        override fun dock() = emptyList<String>()
        override fun saveDock(order: List<String>) = true
        override fun now(): BridgeNow? = throw IllegalStateException("the system went away")
        override fun media(action: MediaAction) = true
        override fun shortcut(shortcut: Shortcut) = true
    }

    private lateinit var bridge: RemoteBridge

    @Before
    fun start() {
        bridge = RemoteBridge(Routes(tv), port = 0)
        bridge.start()
        val until = System.currentTimeMillis() + 5000
        while (bridge.boundPort <= 0 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("the bridge didn't start", bridge.boundPort > 0)
    }

    @After
    fun stop() {
        bridge.stop()
    }

    /** Sends [request] as written and returns the whole response. */
    private fun send(request: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", bridge.boundPort), 2000)
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            return String(s.getInputStream().readBytes(), Charsets.UTF_8)
        }
    }

    private fun get(target: String) = send("GET $target HTTP/1.1\r\nHost: tv:5512\r\nUser-Agent: test\r\nAccept: */*\r\n\r\n")

    private fun body(response: String) = response.substringAfter("\r\n\r\n")

    private fun header(response: String, name: String) =
        response.substringBefore("\r\n\r\n").lines().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    @Test
    fun answersTheRootAsText() {
        val r = get("/")
        assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals("text/plain; charset=utf-8", header(r, "Content-Type"))
        assertEquals("close", header(r, "Connection"))
        assertEquals("Baron Home", body(r))
    }

    @Test
    fun answersJsonWithItsLengthInBytes() {
        val r = get("/inputs")
        assertEquals("application/json; charset=utf-8", header(r, "Content-Type"))
        val b = body(r)
        assertEquals(b.toByteArray(Charsets.UTF_8).size.toString(), header(r, "Content-Length"))
        assertEquals("Café TV", JSONArray(b).getJSONObject(1).getString("name"))
    }

    @Test
    fun decodesTheQuery() {
        val r = get("/source?name=HDMI%201")
        assertTrue(JSONObject(body(r)).getBoolean("ok"))
        assertEquals(listOf("HDMI 1"), sources)
        assertEquals(40, JSONObject(body(get("/volume?step=up"))).getInt("level"))
    }

    @Test
    fun aBareRequestLineIsEnough() {
        assertEquals("Baron Home", body(send("GET / HTTP/1.0\n\n")))
    }

    @Test
    fun unknownPathsAre404AndGarbage400() {
        assertTrue(get("/nothing").startsWith("HTTP/1.1 404 "))
        assertTrue(send("hello\r\n\r\n").startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun aFailingCallIs500AndTheBridgeCarriesOn() {
        assertTrue(get("/now").startsWith("HTTP/1.1 500 "))
        assertEquals("Baron Home", body(get("/")))
    }

    @Test
    fun requestsAreAnsweredSideBySide() {
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads = (1..8).map { Thread { bodies += body(get("/")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(List(8) { "Baron Home" }, bodies.toList())
    }

    @Test
    fun readsLinesWithoutTheirEnds() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\n\r\n".toByteArray())
        assertEquals("GET / HTTP/1.1", RemoteBridge.readLine(input))
        assertEquals("Host: x", RemoteBridge.readLine(input))
        assertEquals("", RemoteBridge.readLine(input))
        assertEquals(null, RemoteBridge.readLine(input))
    }
}
