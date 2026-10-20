package com.barontv.home.bridge

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.barontv.home.core.HomeHost
import com.barontv.home.home.DockApp
import com.barontv.home.home.DockApps
import com.barontv.home.home.DockOrder
import com.barontv.home.side.Inputs
import com.barontv.home.side.PictureSettings
import com.barontv.home.side.SideWorker
import com.barontv.home.side.TvInput
import com.barontv.home.side.TvInputSource
import com.barontv.home.volume.TvVolume
import com.barontv.home.volume.VolumeLevel
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bridge's TV, inside the Input key's accessibility service: the volume through TvVolume (which
 * shows the home's bar), the inputs and their switch through the side dock's own code, the apps in the
 * dock's order, what's playing from the window events and, for a music app, its screen. Called on the
 * bridge's request threads; anything that must happen on the main thread is posted there.
 */
internal class LiveTv(
    private val context: Context,
    private val volume: TvVolume,
    /** The app in front, from the service's window events. */
    private val front: () -> String,
    root: () -> AccessibilityNodeInfo?,
    private val onWake: () -> Unit,
    private val onInput: () -> Unit,
) : BridgeTv {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val screen = ScreenReader(root)
    private val inputWorker = SideWorker()
    private val inputSource = TvInputSource(context, inputWorker)

    // The side dock's switch asks its host to open things; from the service there's no toast to show.
    private val host = object : HomeHost {
        override fun toast(text: CharSequence) {
            Log.i(TAG, text.toString())
        }

        override fun launch(intent: Intent): Boolean = start(intent)

        override fun launchApp(packageName: String): Boolean = launch(packageName)
    }

    override fun wake() {
        main.post(onWake)
    }

    override fun openInputs() {
        main.post(onInput)
    }

    // ---- Volume -------------------------------------------------------------------------------------------

    override fun volume(): VolumeLevel = volume.read()

    override fun stepVolume(step: VolumeStep): VolumeLevel = when (step) {
        VolumeStep.UP -> volume.step(up = true)
        VolumeStep.DOWN -> volume.step(up = false)
        VolumeStep.MUTE -> volume.toggleMute()
    }

    override fun setVolume(percent: Int): VolumeLevel = volume.set(percent)

    // ---- Inputs -------------------------------------------------------------------------------------------

    override fun inputs(): List<BridgeInput> = loadInputs().map { BridgeInput(it.label, Inputs.live(it)) }

    override fun switchInput(name: String): Boolean {
        val want = key(name)
        val input = loadInputs().firstOrNull { key(it.label) == want } ?: return false
        inputSource.switchTo(input, host)
        return true
    }

    /** The side dock's list (TvInputManager's, read on its worker, answered on the main thread). */
    private fun loadInputs(): List<TvInput> {
        val done = CountDownLatch(1)
        var list: List<TvInput>? = null
        inputSource.load {
            list = it
            done.countDown()
        }
        done.await(INPUTS_WAIT_MS, TimeUnit.MILLISECONDS)
        return list ?: Inputs.BARON
    }

    /** "HDMI 1", "hdmi1" and "HDMI-1" are the same input. */
    private fun key(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // ---- Apps and the dock ------------------------------------------------------------------------------

    override fun apps(): List<BridgeApp> = dockApps().map { BridgeApp(it.label, it.packageName, DockApps.isPinned(it.packageName)) }

    override fun dock(): List<String> = dockApps().map { it.packageName }

    override fun saveDock(order: List<String>): Boolean {
        val known = dock().toSet()
        val kept = order.filter { it in known }
        // Only packages this TV doesn't have: nothing to save, and the order stays as it was. (An empty
        // order on purpose goes back to the usual one.)
        if (kept.isEmpty() && order.isNotEmpty()) return false
        return DockOrder.save(context, kept)
    }

    private fun dockApps(): List<DockApp> =
        DockApps.order(DockApps.installed(context.packageManager), DockOrder.load(context))

    // Android 9 has no package visibility rules: every app is seen, as the dock sees them.
    @SuppressLint("QueryPermissionsNeeded")
    override fun launch(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent)
    }

    /** Opens an activity from the service (a new task, as there's none to join). */
    private fun start(intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // ---- What's playing ---------------------------------------------------------------------------------

    /**
     * The app in front, if it plays: music sounding (any app), or a music or video app in front even
     * while paused. Nothing plays behind the home screen on this TV (the firmware stops the app you leave),
     * so with the home in front it's nothing. Title, artist and times only come from a music app's screen.
     */
    override fun now(): BridgeNow? {
        val app = front().ifEmpty { screen.activePackage() }
        if (app.isEmpty() || app == context.packageName) return null
        val playing = audio?.isMusicActive == true
        val music = app in NowApps.MUSIC
        if (!playing && !music && app !in NowApps.VIDEO) return null
        val track = if (music) screen.read(app) else ScreenTrack.NONE
        return BridgeNow(app, appName(app), playing, track.title, track.artist, track.positionMs, track.durationMs)
    }

    private fun appName(packageName: String): String {
        DockApps.PINNED.firstOrNull { it.packageName == packageName }?.let { return it.label }
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // ---- Media keys and shortcuts -------------------------------------------------------------------------

    /** The key goes to whichever app's media session has the media buttons, pressed and released. */
    override fun media(action: MediaAction): Boolean {
        val am = audio ?: return false
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, action.keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, action.keyCode, 0))
        return true
    }

    override fun shortcut(shortcut: Shortcut): Boolean = when (shortcut) {
        // As the side dock's Picture tile: the TV's own picture menu if it opens, else Settings.
        Shortcut.PICTURE -> start(PictureSettings.find(context) ?: Intent(Settings.ACTION_SETTINGS))
        Shortcut.SOUND -> {
            volume.showNow()
            true
        }
        Shortcut.SETTINGS -> start(Intent(Settings.ACTION_SETTINGS))
    }

    fun release() {
        inputWorker.stop()
    }

    private companion object {
        const val TAG = "BaronBridge"

        /** TvInputManager answers in a moment; past this the bridge answers with Baron's three inputs. */
        const val INPUTS_WAIT_MS = 3000L
    }
}

/** Which apps count for what's playing. */
internal object NowApps {
    /** Music apps, whose screens are read for the song: the same list music mode keeps (music/OverlayMusic.kt). */
    val MUSIC = setOf(
        "com.spotify.tv.android",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.bsbportal.music",
        "com.amazon.music.tv",
        "com.apple.android.music",
    )

    /** Film and TV apps: in front, they're what's playing even while paused. */
    val VIDEO = setOf(
        "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom",
        "com.netflix.ninja",
        "in.startv.hotstar",
        "com.cvte.tv.media",
        "org.videolan.vlc",
        "org.xbmc.kodi",
        "com.plexapp.android",
        "org.jellyfin.androidtv",
    )
}
