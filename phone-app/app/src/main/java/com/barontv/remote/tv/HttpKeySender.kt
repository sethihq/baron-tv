package com.barontv.remote.tv

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The request CloudWalker's server turns into a key press: `GET /input/?keycode=N`. */
internal fun keyUrl(address: TvAddress, code: Int): URL =
    URL("http", address.host, address.port, "/input/?keycode=$code")

/**
 * The Baron home screen's bridge (home/ RemoteBridge), next to CloudWalker's server. Keys that server
 * injects never reach the home's accessibility service, so each press also nudges the bridge (music mode
 * over the music app restarts its idle clock, or wakes), and Input asks it to open the TV's input list.
 * With the old launcher there's no bridge, and the keys work as before.
 */
internal const val BRIDGE_PORT = 5512

internal fun bridgeUrl(address: TvAddress, path: String): URL = URL("http", address.host, BRIDGE_PORT, path)

/**
 * Sends key presses the way CloudWalker's server takes them, one HTTP GET each.
 *
 * A press only counts as failed when the TV can't be reached: the connection is refused or times out.
 * Once the TV has taken the request the key has most likely landed, so a slow or odd reply isn't read as
 * the TV going away (the regular check notices if it really has).
 */
internal class HttpKeySender(
    // Never through a proxy: the TV is on the home network.
    private val open: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 2_000,
) : KeySender {

    // Nudges go on their own thread, so a missing bridge never slows a key down.
    private val nudges = Executors.newSingleThreadExecutor { r -> Thread(r, "bridge").apply { isDaemon = true } }

    @Volatile
    private var lastNudge = 0L

    override suspend fun send(address: TvAddress, code: Int): Boolean {
        // Input through the bridge opens the input list; injected 178 can't (see bridgeUrl).
        if (code == TvKey.INPUT.code) {
            val url = bridgeUrl(address, "/input")
            if (blockingNetworkCall(io, abort = {}) { quietGet(url, BRIDGE_TIMEOUT_MS) }) {
                nudge(address)
                return true
            }
        }
        val connection = try {
            open(keyUrl(address, code)) as HttpURLConnection
        } catch (e: IOException) {
            return false
        }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        // A new connection for every press. Reusing one the TV has quietly closed can make the HTTP stack
        // send the request again, and a request sent twice is a key pressed twice.
        connection.setRequestProperty("Connection", "close")
        val sent = blockingNetworkCall(io, abort = { connection.disconnect() }) { exchange(connection) }
        if (sent) nudge(address)
        return sent
    }

    /** Tells the bridge a key was pressed, at most once a second, and never waits for it. */
    private fun nudge(address: TvAddress) {
        val now = System.currentTimeMillis()
        if (now - lastNudge < NUDGE_EVERY_MS) return
        lastNudge = now
        val url = bridgeUrl(address, "/wake")
        nudges.execute { quietGet(url, BRIDGE_TIMEOUT_MS) }
    }

    /** A GET that only says whether it was answered with 200; any failure is just false. */
    private fun quietGet(url: URL, timeoutMs: Int): Boolean = try {
        val c = open(url) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.setRequestProperty("Connection", "close")
        try {
            val ok = c.responseCode == 200
            (if (ok) c.inputStream else c.errorStream)?.use(::drain)
            ok
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        false
    } catch (e: ClassCastException) {
        false
    }

    private fun exchange(connection: HttpURLConnection): Boolean {
        try {
            try {
                connection.connect()
            } catch (e: IOException) {
                return false
            }
            try {
                val reply = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
                reply?.use(::drain)
            } catch (e: IOException) {
                // Taken, but the reply was cut short or odd: see the class comment.
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val NUDGE_EVERY_MS = 1_000L
        const val BRIDGE_TIMEOUT_MS = 800
    }

    /** Reads the reply ("OK") to the end and throws it away, so the exchange finishes cleanly. */
    private fun drain(stream: InputStream) {
        val buffer = ByteArray(256)
        while (stream.read(buffer) != -1) {
            // Nothing in it to keep.
        }
    }
}
