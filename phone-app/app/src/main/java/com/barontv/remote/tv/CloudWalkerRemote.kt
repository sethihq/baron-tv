package com.barontv.remote.tv

import android.content.Context
import android.util.Log
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** What the screen calls the TV. Its mDNS name (`CloudTV_<ethernet MAC>`) means nothing to anyone. */
internal const val TV_NAME = "Baron TV"

/** The port CloudWalker's phone-remote server (`tv.cloudwalker.inputserver`) listens on. */
internal const val CLOUDWALKER_PORT = 5511

/** The mDNS service type that server announces. */
internal const val CLOUDWALKER_SERVICE = "_cloudwalker._tcp"

/** The phone app's logcat tag, for the remote and the bridge alike. */
internal const val LOG_TAG = "BaronRemote"

/**
 * How long each step may take. On a home Wi-Fi the TV accepts a connection within milliseconds when it's
 * there, so the timeouts are short and a search is over in seconds.
 */
internal data class Timings(
    /** The last good address gets one quick try before anything else. */
    val rememberedTimeoutMs: Int = 400,
    /** An address from mDNS is checked before it's used: mDNS caches can hold the address from the last boot. */
    val confirmTimeoutMs: Int = 800,
    /** Per address in the subnet scan. Most addresses have nothing on them, so this sets the scan's pace. */
    val scanTimeoutMs: Int = 300,
    /** Addresses knocked on at once: a /24 takes four rounds. */
    val scanParallelism: Int = 64,
    /** mDNS usually answers within a second; it gets this long alone before the scan joins in. */
    val mdnsHeadStartMs: Long = 2_000,
    /** The whole search. With no answer by then, the TV is NotFound. */
    val searchLimitMs: Long = 7_000,
    /** How often a connected TV is checked. */
    val checkEveryMs: Long = 5_000,
    /** Each check's timeout: generous, so a busy moment on the Wi-Fi isn't taken for the TV leaving. */
    val checkTimeoutMs: Int = 1_000,
    /** Presses waiting to be sent. Past this, new presses are dropped rather than holding up the screen. */
    val keyQueue: Int = 32,
)

/**
 * The phone's line to the TV: finds CloudWalker's remote server on the Wi-Fi, keeps checking it's still
 * there, and sends it key presses.
 *
 * The TV gets a new address at every boot, so it's looked for each time the app comes on screen, fastest
 * way first:
 * 1. The address it answered on last time, if that still accepts a connection within 400 ms.
 * 2. mDNS, which names the TV directly (`_cloudwalker._tcp`).
 * 3. If mDNS hasn't answered within 2 s (or can't run), every address in the phone's /24 as well, 64 at
 *    a time, nearest the last address first. mDNS keeps listening meanwhile; the first answer wins.
 * With no answer 7 s after the search began, the TV is [TvState.NotFound]. Every check is a TCP connect to
 * port 5511 and nothing more: the server's endpoints all change what the TV shows.
 *
 * Android stays behind the small interfaces in TvParts.kt, so all of this runs in plain JVM tests.
 *
 * [connect] opens HTTP connections to the TV over the same Wi-Fi the keys use, so the home's bridge
 * (HttpTvBridge) reaches the TV exactly as the keys do.
 */
class CloudWalkerRemote internal constructor(
    private val wifi: WifiWatcher,
    private val browser: TvBrowser,
    private val probe: PortProbe,
    private val sender: KeySender,
    private val store: AddressStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timings: Timings = Timings(),
    private val log: (String) -> Unit = {},
    internal val connect: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) },
) : TvRemote {

    /** The remote for whichever TV is on this phone's Wi-Fi. */
    constructor(context: Context) : this(AndroidTvParts(context.applicationContext ?: context))

    private constructor(parts: AndroidTvParts) : this(
        wifi = parts.wifi,
        browser = parts.browser,
        probe = parts.probe,
        sender = parts.sender,
        store = parts.store,
        log = parts.log,
        connect = parts.route::open,
    )

    private val _state = MutableStateFlow<TvState>(TvState.Searching)
    override val state: StateFlow<TvState> = _state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("Stopped by an error: $e") },
    )
    private val lock = Any()
    private var running: Job? = null

    /** Bumped by [retry]: each new value starts the search over. */
    private val restarts = MutableStateFlow(0)

    /** The connected TV's queue of presses; null whenever there's no TV to send to. */
    private val presses = AtomicReference<SendChannel<TvKey>?>(null)

    override fun start() {
        synchronized(lock) {
            if (running?.isActive == true) return
            val stopped = running
            running = scope.launch {
                // A stopped run may still be letting go of a socket. Wait, so two runs never overlap.
                stopped?.join()
                follow()
            }
        }
    }

    override fun stop() {
        synchronized(lock) { running?.cancel() }
        presses.set(null)
    }

    override fun retry() {
        restarts.update { it + 1 }
    }

    override fun send(key: TvKey) {
        // trySend never waits: with no TV, or with the queue full, the press is dropped.
        presses.get()?.trySend(key)
    }

    /** Follows the Wi-Fi: a fresh search for every new network (and every retry), NoWifi without one. */
    private suspend fun follow() {
        combine(wifi.links(), restarts) { link, _ -> link }.collectLatest { link ->
            if (link == null) {
                log("No Wi-Fi")
                _state.value = TvState.NoWifi
                return@collectLatest
            }
            try {
                keepConnected(link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("The search broke: $e")
                _state.value = TvState.NotFound
            }
        }
    }

    /** Finds the TV on [link] and stays with it, looking again whenever it stops answering. */
    private suspend fun keepConnected(link: WifiLink) {
        log("On Wi-Fi as ${link.ipv4 ?: "(no IPv4 address yet)"}/${link.prefixLength}")
        // Starting over while connected (back on screen, a retry, a change of Wi-Fi): check the TV quietly
        // before saying "Searching", so the screen doesn't flicker when nothing has really changed.
        var quiet = _state.value is TvState.Connected
        while (true) {
            val tv = search(link, quiet) ?: run {
                _state.value = TvState.NotFound
                return // until retry(), a change of Wi-Fi, or the next start()
            }
            quiet = false
            stayConnected(tv)
        }
    }

    /** One search, at most [Timings.searchLimitMs] long. Returns where the TV answered, or null. */
    private suspend fun search(link: WifiLink, quiet: Boolean): TvAddress? {
        if (!quiet) _state.value = TvState.Searching
        val last = attempt("Reading the last address") { store.load() }
        val found = withTimeoutOrNull(timings.searchLimitMs) {
            if (last != null && probe.isOpen(last, timings.rememberedTimeoutMs)) {
                log("The TV answered at its last address, ${last.host}")
                return@withTimeoutOrNull last
            }
            _state.value = TvState.Searching
            byNameOrScan(link, last)
        }
        when (found) {
            null -> log("Nothing answered within ${timings.searchLimitMs} ms")
            last -> {}
            else -> {
                log("Found the TV at ${found.host}:${found.port}")
                attempt("Saving the address") { store.save(found) }
            }
        }
        return found
    }

    /**
     * mDNS and the subnet scan side by side, mDNS with a head start since it names the TV directly. If
     * mDNS can't run at all, the scan starts at once. The first address that answers wins.
     */
    private suspend fun byNameOrScan(link: WifiLink, last: TvAddress?): TvAddress? {
        val mdnsGaveUp = CompletableDeferred<Unit>()
        return firstNonNull(
            listOf<suspend () -> TvAddress?>(
                {
                    try {
                        attempt("mDNS") {
                            // mDNS caches can be a boot out of date, so each address it gives is checked.
                            browser.find().firstOrNull { probe.isOpen(it, timings.confirmTimeoutMs) }
                        }
                    } finally {
                        mdnsGaveUp.complete(Unit)
                    }
                },
                {
                    withTimeoutOrNull(timings.mdnsHeadStartMs) { mdnsGaveUp.await() }
                    scan(link, last)
                },
            ),
        )
    }

    /**
     * Knocks on port 5511 at every address in the phone's subnet (at most the /24 around the phone),
     * [Timings.scanParallelism] at a time, nearest the TV's last address first.
     */
    private suspend fun scan(link: WifiLink, last: TvAddress?): TvAddress? {
        val phone = link.ipv4 ?: return null
        val hosts = nearestFirst(subnetHosts(phone, link.prefixLength), near = last?.host)
        log("Scanning ${hosts.size} addresses")
        val gate = Semaphore(timings.scanParallelism)
        return firstNonNull(
            hosts.map { host ->
                suspend {
                    gate.withPermit {
                        TvAddress(host).takeIf { probe.isOpen(it, timings.scanTimeoutMs) }
                    }
                }
            },
        )
    }

    /** Sends [tv] its key presses and checks on it; returns once it has stopped answering. */
    private suspend fun stayConnected(tv: TvAddress) {
        val queue = Channel<TvKey>(timings.keyQueue)
        val gone = CompletableDeferred<String>()
        coroutineScope {
            // One worker, one press at a time, so presses reach the TV in the order they were made.
            launch {
                for (key in queue) {
                    if (!sender.send(tv, key.code)) {
                        gone.complete("a key press didn't reach it")
                        break
                    }
                }
            }
            launch {
                while (true) {
                    delay(timings.checkEveryMs)
                    // A second try before giving up, so one lost packet doesn't drop the connection.
                    if (!probe.isOpen(tv, timings.checkTimeoutMs) && !probe.isOpen(tv, timings.checkTimeoutMs)) {
                        gone.complete("it stopped answering")
                        break
                    }
                }
            }
            presses.set(queue)
            _state.value = TvState.Connected(TV_NAME, tv.host)
            try {
                log("Lost the TV: ${gone.await()}")
            } finally {
                // Presses made for this TV are dropped rather than sent to whatever is found next.
                presses.compareAndSet(queue, null)
                queue.cancel()
                coroutineContext.cancelChildren()
            }
        }
    }

    /** Runs [block], logging and returning null if it throws: one broken step shouldn't end the search. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: $e")
            null
        }
}

/**
 * Runs [tasks] side by side and returns the first answer that isn't null, cancelling the rest at that
 * point; null once every task has come back empty.
 */
internal suspend fun <T : Any> firstNonNull(tasks: List<suspend () -> T?>): T? = coroutineScope {
    val answers = Channel<T?>(Channel.UNLIMITED)
    for (task in tasks) launch { answers.send(task()) }
    repeat(tasks.size) {
        val answer = answers.receive()
        if (answer != null) {
            coroutineContext.cancelChildren()
            return@coroutineScope answer
        }
    }
    null
}

/** The Android side of [CloudWalkerRemote]: the real Wi-Fi, mDNS, sockets and storage. */
internal class AndroidTvParts(context: Context) {
    val log: (String) -> Unit = { Log.d(LOG_TAG, it) }
    val wifi = AndroidWifiWatcher(context, log)
    val route = WifiRoute(wifi)
    val browser = NsdTvBrowser(context, log)
    val probe = TcpProbe(newSocket = route::newSocket)
    val sender = HttpKeySender(open = route::open)
    val store = PrefsAddressStore(context)
}
