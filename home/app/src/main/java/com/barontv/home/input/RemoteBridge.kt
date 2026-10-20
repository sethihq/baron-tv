package com.barontv.home.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A tiny HTTP server for the phone app, on [PORT]. The phone sends its keys to CloudWalker's own server
 * (port 5511), which injects them, and injected keys never reach accessibility services: so the Input
 * key's source panel and music mode over the music app can't see them. The phone also calls here:
 *   GET /wake   a key was pressed on the phone: restart music mode's idle clock (and wake it if it shows)
 *   GET /input  open CVTE's source panel, as the remote's Input key does
 *   GET /       "Baron Home", so the phone knows the bridge is there
 * Nothing here reads or changes anything else, so like CloudWalker's server it needs no pairing.
 * One background thread; each request is a line or two and is answered at once.
 */
class RemoteBridge(private val onWake: () -> Unit, private val onInput: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        val t = Thread({ serve() }, "remote-bridge")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
        }
        server = null
        thread = null
    }

    private fun serve() {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Can't listen on $PORT: $e")
            return
        }
        server = s
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            try {
                answer(client)
            } catch (e: IOException) {
                // A phone that went away mid-request: nothing to do.
            } finally {
                try {
                    client.close()
                } catch (e: IOException) {
                }
            }
        }
    }

    private fun answer(client: Socket) {
        client.soTimeout = 2000
        val line = try {
            client.getInputStream().bufferedReader().readLine() ?: return
        } catch (e: SocketTimeoutException) {
            return
        }
        // "GET /wake HTTP/1.1"
        val path = line.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val body = when (path) {
            "/wake" -> {
                main.post(onWake)
                "OK"
            }
            "/input" -> {
                main.post(onInput)
                "OK"
            }
            "/" -> "Baron Home"
            else -> null
        }
        val status = if (body != null) "200 OK" else "404 Not Found"
        val text = body ?: "Not found"
        client.getOutputStream().write(
            ("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text")
                .toByteArray(),
        )
    }

    companion object {
        /** Next to CloudWalker's 5511. */
        const val PORT = 5512
        private const val TAG = "BaronBridge"
    }
}
