package com.barontv.home.input

import android.util.Log
import com.barontv.home.bridge.Reply
import com.barontv.home.bridge.Request
import com.barontv.home.bridge.Routes
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP server for the phone app, on [PORT] (home/BRIDGE.md is its API, Routes its answers). The
 * phone sends its keys to CloudWalker's own server (port 5511), which injects them, and injected keys never
 * reach accessibility services; everything else it asks of the TV comes here. Nothing here reads anything
 * private, and the actions are what the remote can already do, so like CloudWalker's server it needs no
 * pairing.
 *
 * One thread only accepts; each connection is answered on one of a few request threads, so a slow answer
 * (reading the screen for /now, a busy system) never holds up the next request. The threads are fixed:
 * the accessibility framework keeps a client per thread that reads the screen.
 */
class RemoteBridge(private val routes: Routes, private val port: Int = PORT) {
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var stopped = false
    private var thread: Thread? = null
    private val pool = ThreadPoolExecutor(
        THREADS,
        THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(QUEUE),
    ) { r -> Thread(r, "bridge-request").apply { isDaemon = true } }

    /** The port it listens on once it's up, else -1 (the tests ask for any free port). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        if (thread != null) return
        val t = Thread({ serve() }, "remote-bridge")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        stopped = true
        try {
            server?.close()
        } catch (e: IOException) {
        }
        server = null
        thread = null
        pool.shutdownNow()
    }

    private fun serve() {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Can't listen on $port: $e")
            return
        }
        server = s
        // Stopped while it was still binding: let the port go again.
        if (stopped) {
            close(s)
            return
        }
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: RejectedExecutionException) {
                // A flood of requests, or stopping: this one goes unanswered.
                close(client)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = READ_MS
            val input = BufferedInputStream(client.getInputStream())
            val line = readLine(input) ?: return
            // The headers say nothing the bridge needs, but they're read so closing doesn't reset the
            // connection under a phone that hasn't read the answer yet.
            for (i in 0 until MAX_HEADERS) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val reply = Request.parse(line)?.let { answer(it) } ?: Reply.text("Bad request", 400)
            val out = client.getOutputStream()
            out.write(reply.bytes())
            out.flush()
        } catch (e: IOException) {
            // A phone that went away mid-request: nothing to do.
        } finally {
            close(client)
        }
    }

    private fun answer(request: Request): Reply = try {
        routes.answer(request)
    } catch (e: RuntimeException) {
        // A system service that died under a call, say: the phone gets an error, the bridge carries on.
        Log.w(TAG, "${request.path} failed: $e")
        Reply.text("Something went wrong", 500)
    }

    private fun close(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
        }
    }

    companion object {
        /** Next to CloudWalker's 5511. */
        const val PORT = 5512
        private const val TAG = "BaronBridge"
        private const val THREADS = 3
        private const val QUEUE = 32
        private const val READ_MS = 2000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64

        /**
         * One line of the request without its CR LF, decoded as UTF-8 (clients escape anything else; a raw
         * byte decodes as best it can); null at the end of the stream. An overlong line is cut short.
         */
        internal fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var any = false
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (!any) return null
                    break
                }
                any = true
                if (b == '\n'.code) break
                if (bytes.size() < MAX_LINE) bytes.write(b)
            }
            val line = String(bytes.toByteArray(), Charsets.UTF_8)
            return if (line.endsWith('\r')) line.dropLast(1) else line
        }
    }
}
