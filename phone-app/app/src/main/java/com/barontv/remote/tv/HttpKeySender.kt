package com.barontv.remote.tv

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The request CloudWalker's server turns into a key press: `GET /input/?keycode=N`. */
internal fun keyUrl(address: TvAddress, code: Int): URL =
    URL("http", address.host, address.port, "/input/?keycode=$code")

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

    override suspend fun send(address: TvAddress, code: Int): Boolean {
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
        return blockingNetworkCall(io, abort = { connection.disconnect() }) { exchange(connection) }
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

    /** Reads the reply ("OK") to the end and throws it away, so the exchange finishes cleanly. */
    private fun drain(stream: InputStream) {
        val buffer = ByteArray(256)
        while (stream.read(buffer) != -1) {
            // Nothing in it to keep.
        }
    }
}
