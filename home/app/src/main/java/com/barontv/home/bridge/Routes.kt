package com.barontv.home.bridge

import android.view.KeyEvent
import com.barontv.home.volume.VolumeLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the bridge asks of the TV. LiveTv is the real one, inside the accessibility service; the tests
 * use a stand-in. Called on the bridge's request threads, so each call may take a moment (a binder call,
 * reading the screen) but never waits long.
 */
interface BridgeTv {
    /** A key was pressed on the phone (it reaches the TV injected, which the service never sees). */
    fun wake()

    /** CVTE's source panel, as the remote's Input key opens it. */
    fun openInputs()

    fun volume(): VolumeLevel

    /** The level after the change; the TV shows the home's volume bar for it. */
    fun stepVolume(step: VolumeStep): VolumeLevel

    fun setVolume(percent: Int): VolumeLevel

    fun inputs(): List<BridgeInput>

    /** False if no input has that name. */
    fun switchInput(name: String): Boolean

    /** The TV's apps in the dock's order. */
    fun apps(): List<BridgeApp>

    fun launch(packageName: String): Boolean

    /** The dock's packages, first to last. */
    fun dock(): List<String>

    /** Saves the order: unknown packages dropped, false if that leaves none; empty goes back to the usual order. */
    fun saveDock(order: List<String>): Boolean

    /** What's playing, or null when nothing is. */
    fun now(): BridgeNow?

    fun media(action: MediaAction): Boolean

    fun shortcut(shortcut: Shortcut): Boolean
}

enum class VolumeStep { UP, DOWN, MUTE }

/** The phone's transport buttons, as the media keys the TV understands. */
enum class MediaAction(val keyCode: Int) {
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
}

enum class Shortcut { PICTURE, SOUND, SETTINGS }

data class BridgeInput(val name: String, val live: Boolean)

/** [pinned]: one of the user's own dock apps (with its cover), as against an app that came after. */
data class BridgeApp(val name: String, val packageName: String, val pinned: Boolean)

/** [title] and [artist] are empty and the times -1 when they can't be read. */
data class BridgeNow(
    val packageName: String,
    val appName: String,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The bridge's API, home/BRIDGE.md, from request to reply: every answer is 200 with JSON (or plain text
 * for /, /wake and /input), an unknown path is 404. A parameter that makes no sense never fails the
 * request: /volume answers the level as it is, the actions answer {"ok":false}.
 */
class Routes(private val tv: BridgeTv) {

    fun answer(request: Request): Reply {
        if (request.method != "GET") return Reply.text("Only GET here", 405)
        return when (request.path) {
            "/" -> Reply.text("Baron Home")
            "/wake" -> {
                tv.wake()
                Reply.text("OK")
            }
            "/input" -> {
                tv.openInputs()
                Reply.text("OK")
            }
            "/volume" -> Reply.json(volume(request))
            "/inputs" -> Reply.json(JSONArray(tv.inputs().map { JSONObject().put("name", it.name).put("live", it.live) }))
            "/source" -> ok(request["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tv.switchInput(it) } ?: false)
            "/apps" -> Reply.json(
                JSONArray(tv.apps().map { JSONObject().put("name", it.name).put("package", it.packageName).put("pinned", it.pinned) }),
            )
            "/launch" -> ok(request["package"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tv.launch(it) } ?: false)
            "/dock" -> if (request.has("order")) ok(tv.saveDock(order(request["order"].orEmpty()))) else Reply.json(JSONArray(tv.dock()))
            "/now" -> Reply.json(now(tv.now()))
            "/media" -> ok(media(request["action"])?.let { tv.media(it) } ?: false)
            "/shortcut" -> ok(shortcut(request["name"])?.let { tv.shortcut(it) } ?: false)
            else -> Reply.NOT_FOUND
        }
    }

    private fun volume(request: Request): JSONObject {
        val step = request["step"]
        val set = request["set"]
        val level = when {
            step != null -> when (step.trim().lowercase(Locale.ROOT)) {
                "up" -> tv.stepVolume(VolumeStep.UP)
                "down" -> tv.stepVolume(VolumeStep.DOWN)
                "mute" -> tv.stepVolume(VolumeStep.MUTE)
                else -> tv.volume()
            }
            // The slider may send "40" or "40.0"; anything else leaves the volume alone.
            set != null -> set.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { tv.setVolume(it.roundToInt().coerceIn(0, 100)) } ?: tv.volume()
            else -> tv.volume()
        }
        return JSONObject().put("level", level.percent).put("muted", level.muted)
    }

    private fun now(now: BridgeNow?): JSONObject {
        if (now == null) return JSONObject()
        return JSONObject()
            .put("app", now.packageName)
            .put("appName", now.appName)
            .put("playing", now.playing)
            .put("title", now.title)
            .put("artist", now.artist)
            .put("positionMs", now.positionMs)
            .put("durationMs", now.durationMs)
    }

    private fun ok(done: Boolean) = Reply.json(JSONObject().put("ok", done))

    companion object {
        /** "pkgA,pkgB" as [pkgA, pkgB]: blanks and repeats dropped. */
        fun order(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun media(raw: String?): MediaAction? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "playpause" -> MediaAction.PLAY_PAUSE
            "next" -> MediaAction.NEXT
            "previous" -> MediaAction.PREVIOUS
            else -> null
        }

        fun shortcut(raw: String?): Shortcut? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "picture" -> Shortcut.PICTURE
            "sound" -> Shortcut.SOUND
            "settings" -> Shortcut.SETTINGS
            else -> null
        }
    }
}
