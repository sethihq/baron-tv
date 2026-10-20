package com.barontv.remote.tv

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What the bridge answers to `GET /`, which tells it apart from anything else on that port. */
internal const val BRIDGE_HELLO = "Baron Home"

/** The longest reply read. The longest the bridge sends, the list of apps, is a few KB. */
private const val MAX_REPLY_BYTES = 256 * 1024

/** How long the bridge client waits, and how often it looks for the bridge. */
internal data class BridgeTimings(
    /** Each request's connect and read timeout. On the home Wi-Fi the TV answers in tens of milliseconds. */
    val timeoutMs: Int = 1_500,
    /** How often a connected TV is asked whether its bridge is still there (or there now). */
    val checkEveryMs: Long = 15_000,
)

/**
 * The phone's side of the Baron home's bridge: plain HTTP to port 5512 on the TV that [remote] is
 * connected to (home/BRIDGE.md has the API). The bridge is [available] once `GET /` answers
 * "Baron Home", which is checked as soon as the remote connects to a TV and every 15 s after, while
 * started. The activity calls [start] and [stop] as the app comes on and off screen, as for the remote.
 *
 * Without the bridge (the old launcher, or the home's accessibility service off), every call falls back
 * as TvBridge promises: [launch] asks CloudWalker's own server (`GET /play?packageName=` on port 5511),
 * [changeVolume] sends the volume key through [remote] and returns null, and the rest return null, an
 * empty list or false. Those two also fall back on the spot when the request never reached the bridge:
 * nothing took the connection (the bridge is then taken as gone until the next check finds it), or it
 * answered 404 (an older bridge that doesn't know that request yet). A bridge that took a request but
 * answered badly or too slowly isn't asked another way: the change may well have happened, and twice is
 * worse than once.
 *
 * Each request has a short timeout and runs on [io], never on the caller's thread, and a cancelled call
 * lets go of its connection at once.
 */
class HttpTvBridge internal constructor(
    private val remote: TvRemote,
    private val connect: (URL) -> URLConnection,
    private val io: CoroutineDispatcher,
    private val bridgePort: Int,
    private val remotePort: Int,
    private val timings: BridgeTimings,
    private val log: (String) -> Unit,
) : TvBridge {

    /** The bridge on whichever TV [remote] is connected to, reached over the same Wi-Fi as its keys. */
    constructor(remote: TvRemote) : this(
        remote = remote,
        connect = (remote as? CloudWalkerRemote)?.connect ?: { it.openConnection(Proxy.NO_PROXY) },
        io = Dispatchers.IO,
        bridgePort = BRIDGE_PORT,
        remotePort = CLOUDWALKER_PORT,
        timings = BridgeTimings(),
        log = { Log.d(LOG_TAG, it) },
    )

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Where the bridge answered the last check; null while there's no bridge to talk to. */
    @Volatile
    private var bridgeHost: String? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log("Bridge checks stopped: $e") },
    )
    private val lock = Any()
    private var watching: Job? = null

    /** The app is on screen: look for the bridge at every new connection, and every 15 s while connected. */
    fun start() {
        synchronized(lock) {
            if (watching?.isActive == true) return
            watching = scope.launch { watch() }
        }
    }

    /** The app has left the screen: stop looking. What the last check found holds until the next one. */
    fun stop() {
        synchronized(lock) {
            watching?.cancel()
            watching = null
        }
    }

    private suspend fun watch() {
        remote.state.collectLatest { state ->
            if (state !is TvState.Connected) {
                found(null)
                return@collectLatest
            }
            // Another TV, or the same one at a new address: no bridge until that one answers.
            if (bridgeHost != state.host) found(null)
            while (true) {
                found(state.host.takeIf { hasBridge(it) })
                delay(timings.checkEveryMs)
            }
        }
    }

    private suspend fun hasBridge(host: String): Boolean {
        val reply = get(host, bridgePort, "/")
        return reply is Reply.Answered && reply.code == 200 && reply.body.trim().startsWith(BRIDGE_HELLO)
    }

    /** Notes where the bridge answers, or that it doesn't, and logs it when that changes. */
    private fun found(host: String?) {
        val changed = synchronized(lock) {
            val before = bridgeHost
            bridgeHost = host
            _available.value = host != null
            before != host
        }
        if (changed) log(if (host != null) "The home's bridge answers at $host" else "No bridge")
    }

    /** Nothing took a connection to the bridge at [host]: it's gone until a check finds it again. */
    private fun lost(host: String) {
        synchronized(lock) {
            if (bridgeHost != host) return
            bridgeHost = null
            _available.value = false
        }
        log("The bridge at $host stopped taking connections")
    }

    /** The bridge to ask: the connected TV's, while it answers. */
    private fun currentBridge(): String? {
        val host = bridgeHost ?: return null
        return host.takeIf { (remote.state.value as? TvState.Connected)?.host == it }
    }

    /** Asks the bridge for [path]: null when there's no bridge to ask. */
    private suspend fun ask(path: String): Reply? {
        val host = currentBridge() ?: return null
        val reply = get(host, bridgePort, path)
        if (reply == Reply.Unreachable) lost(host)
        return reply
    }

    /** The body of the bridge's answer to [path], when it answered with 200. */
    private suspend fun answer(path: String): String? = ask(path).okBody()

    override suspend fun volume(): Volume? = answer("/volume")?.let(::volumeOf)

    override suspend fun changeVolume(step: VolumeStep): Volume? {
        val reply = ask("/volume?step=${step.word}")
        if (reply.neverArrived()) {
            // The volume key itself: it works without the bridge, but the TV shows CVTE's volume bar.
            remote.send(step.key)
            return null
        }
        return reply.okBody()?.let(::volumeOf)
    }

    override suspend fun setVolume(level: Int): Volume? =
        answer("/volume?set=${level.coerceIn(0, 100)}")?.let(::volumeOf)

    override suspend fun inputs(): List<TvInput> = answer("/inputs")?.let(::inputsOf).orEmpty()

    override suspend fun switchInput(name: String): Boolean = answer("/source?name=${encode(name)}")?.let(::okOf) ?: false

    override suspend fun apps(): List<TvApp> = answer("/apps")?.let(::appsOf).orEmpty()

    override suspend fun launch(packageName: String): Boolean {
        val reply = ask("/launch?package=${encode(packageName)}")
        if (!reply.neverArrived()) return reply.okBody()?.let(::okOf) ?: false
        // CloudWalker's own server opens apps too. It answers OK to anything, so true only means it heard.
        val host = (remote.state.value as? TvState.Connected)?.host ?: return false
        val play = get(host, remotePort, "/play?packageName=${encode(packageName)}")
        return play is Reply.Answered && play.code == 200
    }

    override suspend fun setDock(packages: List<String>): Boolean =
        answer("/dock?order=${packages.joinToString(",") { encode(it) }}")?.let(::okOf) ?: false

    override suspend fun nowPlaying(): NowPlaying? = answer("/now")?.let(::nowPlayingOf)

    override suspend fun media(action: MediaAction): Boolean = answer("/media?action=${action.word}")?.let(::okOf) ?: false

    override suspend fun shortcut(shortcut: Shortcut): Boolean =
        answer("/shortcut?name=${shortcut.word}")?.let(::okOf) ?: false

    /** One GET to [host]:[port][path], on [io], with the short timeouts. */
    private suspend fun get(host: String, port: Int, path: String): Reply {
        val connection = try {
            connect(URL("http", host, port, path)) as HttpURLConnection
        } catch (e: Exception) {
            // A malformed address, or no route to open a connection on: either way nothing gets there.
            return Reply.Unreachable
        }
        connection.connectTimeout = timings.timeoutMs
        connection.readTimeout = timings.timeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        // A new connection for every request, as for the keys: reusing one the TV has quietly closed can
        // make the HTTP stack send the request again, and a change sent twice happens twice.
        connection.setRequestProperty("Connection", "close")
        return blockingNetworkCall(io, abort = { connection.disconnect() }) { exchange(connection) }
    }

    private fun exchange(connection: HttpURLConnection): Reply {
        try {
            try {
                connection.connect()
            } catch (e: Exception) {
                return Reply.Unreachable
            }
            return try {
                val code = connection.responseCode
                val stream = if (code < 400) connection.inputStream else connection.errorStream
                Reply.Answered(code, stream?.use(::readReply).orEmpty())
            } catch (e: ConnectException) {
                // The HTTP stack may hold a pooled connection, so connect() can succeed without touching the
                // network, and a refusal turns up here instead. A refused connection never carried the request.
                Reply.Unreachable
            } catch (e: NoRouteToHostException) {
                Reply.Unreachable
            } catch (e: Exception) {
                Reply.Cut
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The reply as text, up to [MAX_REPLY_BYTES]. */
    private fun readReply(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_REPLY_BYTES) throw IOException("The reply is too long")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** How a request went. */
private sealed interface Reply {
    /** Answered with [code]; [body] is the reply's text. */
    class Answered(val code: Int, val body: String) : Reply

    /** Nothing took the connection: no bridge (or no TV) there, so the request never arrived. */
    data object Unreachable : Reply

    /** Taken, but the answer was cut short or never came: the request may well have landed. */
    data object Cut : Reply
}

/** The reply's text when it was answered with 200, else null. */
private fun Reply?.okBody(): String? = (this as? Reply.Answered)?.takeIf { it.code == 200 }?.body

/** True when the request can't have reached a bridge that knows it: no bridge, nothing there, or 404. */
private fun Reply?.neverArrived(): Boolean =
    this == null || this == Reply.Unreachable || (this is Reply.Answered && code == 404)

private val VolumeStep.word: String
    get() = when (this) {
        VolumeStep.UP -> "up"
        VolumeStep.DOWN -> "down"
        VolumeStep.MUTE -> "mute"
    }

/** The key that does the same without the bridge. */
private val VolumeStep.key: TvKey
    get() = when (this) {
        VolumeStep.UP -> TvKey.VOLUME_UP
        VolumeStep.DOWN -> TvKey.VOLUME_DOWN
        VolumeStep.MUTE -> TvKey.MUTE
    }

private val MediaAction.word: String
    get() = when (this) {
        MediaAction.PLAY_PAUSE -> "playpause"
        MediaAction.NEXT -> "next"
        MediaAction.PREVIOUS -> "previous"
    }

private val Shortcut.word: String
    get() = when (this) {
        Shortcut.PICTURE -> "picture"
        Shortcut.SOUND -> "sound"
        Shortcut.SETTINGS -> "settings"
    }

/** [value] for a query string: percent-encoded, with a space as %20, as BRIDGE.md writes `HDMI%201`. */
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

// The replies, read with org.json, which Android has built in. A reply that doesn't parse reads as no
// answer. Missing fields read as empty or -1, and an entry missing the one field it can't do without
// (an input's name, an app's package, what's playing's app) is left out.

/** `{"level":35,"muted":false}`. */
internal fun volumeOf(json: String): Volume? = parsed {
    val o = JSONObject(json)
    if (o.has("level")) Volume(o.getInt("level").coerceIn(0, 100), o.optBoolean("muted", false)) else null
}

/** `[{"name":"HDMI 1","live":true}, ...]`. */
internal fun inputsOf(json: String): List<TvInput> = objectsIn(json).mapNotNull { o ->
    val name = o.text("name")
    if (name.isEmpty()) null else TvInput(name, o.optBoolean("live", false))
}

/** `[{"name":"YouTube","package":"com.google.android.youtube.tv","pinned":true}, ...]`, in the TV's order. */
internal fun appsOf(json: String): List<TvApp> = objectsIn(json).mapNotNull { o ->
    val packageName = o.text("package")
    if (packageName.isEmpty()) null else TvApp(o.text("name"), packageName, o.optBoolean("pinned", false))
}

/** What's playing, or null for `{}`, which means nothing is. */
internal fun nowPlayingOf(json: String): NowPlaying? = parsed {
    val o = JSONObject(json)
    val app = o.text("app")
    if (app.isEmpty()) {
        null
    } else {
        NowPlaying(
            packageName = app,
            appName = o.text("appName"),
            playing = o.optBoolean("playing", false),
            title = o.text("title"),
            artist = o.text("artist"),
            positionMs = o.optLong("positionMs", -1L),
            durationMs = o.optLong("durationMs", -1L),
        )
    }
}

/** `{"ok":true}`. Anything else is false. */
internal fun okOf(json: String): Boolean = parsed { JSONObject(json).optBoolean("ok", false) } ?: false

private fun objectsIn(json: String): List<JSONObject> = parsed {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}.orEmpty()

/** A text field, or "" when it's missing or null. (Android's optString reads a JSON null as "null".) */
private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name, "")

private inline fun <T> parsed(read: () -> T): T? = try {
    read()
} catch (e: JSONException) {
    null
}
