package com.barontv.remote.tv

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole remote, with the real probe and sender, against a stand-in TV server on this machine. Only
 * the Wi-Fi and mDNS are faked: mDNS points straight at the server, and with no IPv4 address there's
 * no subnet to scan.
 */
class EndToEndTest {

    private fun remoteFor(server: FakeTvServer) = CloudWalkerRemote(
        wifi = FakeWifi(WifiLink(network = "test", ipv4 = null, prefixLength = 24)),
        browser = object : TvBrowser {
            override fun find(): Flow<TvAddress> = flowOf(server.address)
        },
        probe = TcpProbe(),
        sender = HttpKeySender(),
        store = FakeStore(),
        log = ::println,
    )

    @Test
    fun `the remote finds the server and sends it quick presses in order`(): Unit = runBlocking {
        FakeTvServer().use { server ->
            val remote = remoteFor(server)
            try {
                remote.start()
                val state = withTimeout(5_000) { remote.state.first { it is TvState.Connected } }
                assertEquals(TvState.Connected("Baron TV", "127.0.0.1"), state)

                val keys = listOf(
                    TvKey.UP, TvKey.UP, TvKey.UP, TvKey.RIGHT, TvKey.RIGHT, TvKey.DOWN, TvKey.OK, TvKey.BACK,
                    TvKey.VOLUME_UP, TvKey.VOLUME_UP, TvKey.VOLUME_DOWN, TvKey.MUTE, TvKey.PLAY_PAUSE, TvKey.HOME,
                )
                keys.forEach(remote::send) // far faster than a thumb
                withTimeout(5_000) {
                    while (server.keycodes.size < keys.size) delay(10)
                }
                delay(200) // and nothing extra turns up after them
                assertEquals(keys.map { it.code }, server.keycodes.toList())
            } finally {
                remote.stop()
            }
        }
    }

    @Test
    fun `a server that goes away sends the remote back to looking`(): Unit = runBlocking {
        val server = FakeTvServer()
        val remote = remoteFor(server)
        try {
            remote.start()
            withTimeout(5_000) { remote.state.first { it is TvState.Connected } }
            server.close()
            remote.send(TvKey.OK)
            // The press is refused, the search finds nothing (mDNS still points at the closed port).
            withTimeout(10_000) { remote.state.first { it == TvState.NotFound } }
        } finally {
            remote.stop()
            server.close()
        }
    }
}
