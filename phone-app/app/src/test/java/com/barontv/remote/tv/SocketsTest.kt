package com.barontv.remote.tv

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real probe and sender, over real sockets on this machine. */
class SocketsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    /** A port nothing listens on: taken, then let go again. */
    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    @Test
    fun `presses sent quickly reach the server exactly, in order`() = runBlocking {
        FakeTvServer().use { server ->
            val sender = HttpKeySender()
            val keys = TvKey.entries + listOf(TvKey.UP, TvKey.UP, TvKey.OK, TvKey.BACK, TvKey.VOLUME_UP)
            for (key in keys) assertTrue(sender.send(server.address, key.code))
            assertEquals(keys.map { it.code }, server.keycodes.toList())
            assertEquals(keys.map { "/input/?keycode=${it.code}" }, server.requests.toList())
        }
    }

    @Test
    fun `a TV that isn't there is a failed press`() = runBlocking {
        assertFalse(HttpKeySender().send(TvAddress("127.0.0.1", closedPort()), TvKey.OK.code))
    }

    @Test
    fun `a TV that takes the press but answers oddly still counts as reached`() = runBlocking {
        ServerSocket(0, 50, loopback).use { listener ->
            thread(isDaemon = true) {
                listener.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (request.readLine()?.isNotEmpty() == true) {
                        // Read up to the blank line that ends the request.
                    }
                    socket.getOutputStream().write("not what HTTP looks like\r\n".toByteArray())
                }
            }
            assertTrue(HttpKeySender().send(TvAddress("127.0.0.1", listener.localPort), TvKey.OK.code))
        }
    }

    @Test
    fun `a listening port answers the probe, a closed one doesn't`() = runBlocking {
        ServerSocket(0, 50, loopback).use { listener ->
            assertTrue(TcpProbe().isOpen(TvAddress("127.0.0.1", listener.localPort), 400))
        }
        assertFalse(TcpProbe().isOpen(TvAddress("127.0.0.1", closedPort()), 400))
    }

    @Test
    fun `the probe sends nothing at all`() = runBlocking {
        ServerSocket(0, 50, loopback).use { listener ->
            val firstByte = CompletableFuture<Int>()
            thread(isDaemon = true) {
                listener.accept().use { firstByte.complete(it.getInputStream().read()) }
            }
            assertTrue(TcpProbe().isOpen(TvAddress("127.0.0.1", listener.localPort), 400))
            // The server sees the connection close without a single byte: nothing it could act on.
            assertEquals(-1, firstByte.get(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cancelling blocking socket work aborts it and returns at once`() = runBlocking {
        val started = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val call = launch(Dispatchers.Default) {
            blockingNetworkCall(Dispatchers.IO, abort = { aborted.countDown() }) {
                started.countDown()
                // Stands in for a blocking connect or read, which only closing the socket can end.
                aborted.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val before = System.nanoTime()
        call.cancelAndJoin()
        val tookMs = (System.nanoTime() - before) / 1_000_000
        assertEquals(0L, aborted.count)
        assertTrue("cancelling took $tookMs ms", tookMs < 1_000)
    }
}
