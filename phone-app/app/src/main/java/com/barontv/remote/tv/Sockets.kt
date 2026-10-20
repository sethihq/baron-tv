package com.barontv.remote.tv

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Threads for the probes: a slice of [Dispatchers.IO] as wide as the scan, apart from the rest of IO, so
 * 64 probes waiting on empty addresses never hold up a key press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val probeThreads: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(64)

/**
 * Runs blocking socket work on [io] without holding up cancellation. A blocking connect or read doesn't
 * notice a cancelled coroutine, so on cancellation [abort] closes the socket, which makes the blocking
 * call give up at once, and the caller moves on instead of waiting out the timeout. That keeps stop(),
 * retry() and "first answer wins" in the scan prompt.
 */
internal suspend fun <T> blockingNetworkCall(io: CoroutineDispatcher, abort: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        // Closing a socket doesn't block, so this is fine on whichever thread cancels (the main thread,
        // when the activity calls stop()).
        continuation.invokeOnCancellation { runCatching(abort) }
        io.asExecutor().execute { continuation.resumeWith(runCatching(block)) }
    }

/**
 * Knocks on a TCP port: connect, then close at once, sending nothing. The TV's server only sees a
 * connection open and close. Its endpoints all change what the TV shows, so none is used to check.
 */
internal class TcpProbe(
    private val newSocket: () -> Socket = { Socket() },
    private val io: CoroutineDispatcher = probeThreads,
) : PortProbe {

    override suspend fun isOpen(address: TvAddress, timeoutMs: Int): Boolean {
        val socket = try {
            newSocket()
        } catch (e: IOException) {
            return false
        }
        return blockingNetworkCall(io, abort = { closeQuietly(socket) }) {
            try {
                socket.connect(InetSocketAddress(address.host, address.port), timeoutMs)
                true
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false // a port out of range, say: nothing there to talk to either way
            } finally {
                closeQuietly(socket)
            }
        }
    }
}

internal fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (ignored: IOException) {
    }
}
