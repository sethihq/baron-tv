package com.barontv.home.input

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.barontv.home.bridge.LiveTv
import com.barontv.home.bridge.Routes
import com.barontv.home.media.SessionTokens
import com.barontv.home.music.OverlayMusic
import com.barontv.home.volume.TvVolume
import com.barontv.home.volume.VolumeKeys

/**
 * The remote's Input key opens CVTE's source panel, from any app. CVTE remotes send KEYCODE_TV_INPUT
 * (scan code 79), which the firmware only ever answered with a broadcast to the stock launcher, blocked
 * on Android 9; this service catches it instead. Moved here from the old launcher's InputKeyService, which
 * it replaces (one of the two is enabled at a time).
 *
 * The remote's volume keys are the home's too (TvVolume): the volume changes here, with no flags, and the
 * home's own bar shows it instead of CVTE's pop-up. Every other key passes through untouched.
 *
 * It also sees every notification posted, which is how the home screen learns what's playing on this TV:
 * low-RAM Android has no notification access, but a music app's notification carries its media session's
 * token (see SessionTokens). It hosts music mode over the music app in front (OverlayMusic): it sees
 * every key, so it knows when the remote rests, and which app is in front. And it runs the phone's bridge
 * (RemoteBridge, on port 5512), which can read a music app's screen for what's playing.
 */
class InputKeyService : AccessibilityService() {
    private var music: OverlayMusic? = null
    private var volume: TvVolume? = null
    private var tv: LiveTv? = null
    private var bridge: RemoteBridge? = null

    /** The app in front, from the window events; read by the bridge's and the volume's threads. */
    @Volatile
    private var front = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Watch for the service being switched off again while this process lives.
        ServiceGuard.start(this)
        music = OverlayMusic(this)
        val v = TvVolume(this, homeInFront = { front == packageName })
        volume = v
        // The phone app's keys arrive injected, which this service never sees: the phone calls the bridge too.
        val live = LiveTv(
            this,
            v,
            front = { front },
            root = { rootInActiveWindow },
            onWake = { music?.onRemoteActivity() },
            onInput = { openSourcePanel() },
        )
        tv = live
        bridge = RemoteBridge(Routes(live)).also { it.start() }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val v = volume
        if (v != null && VolumeKeys.isVolumeKey(event.keyCode)) {
            // Music mode stays up under the volume bar (it isn't woken by the volume); with it off, the
            // press still counts as the remote in use, so it doesn't start meanwhile.
            if (music?.isOn != true) music?.onKey(event)
            return v.onKey(event)
        }
        if (music?.onKey(event) == true) return true
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_TV_INPUT && code != KeyEvent.KEYCODE_TV) return false
        if (event.action == KeyEvent.ACTION_UP) openSourcePanel()
        return true
    }

    private fun openSourcePanel() {
        try {
            startActivity(Intent(SHOW_INPUT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: ActivityNotFoundException) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The system's own panels (a volume slider, say) don't change who's in front.
                if (pkg != SYSTEM_UI) front = pkg
                music?.onFront(pkg)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notification = event.parcelableData as? Notification ?: return
                SessionTokens.onNotification(this, pkg, notification)
            }
            // Only those two are asked for (input_key_service.xml); the cache's own events never get here.
            else -> Unit
        }
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        tv?.release()
        tv = null
        volume?.release()
        volume = null
        music?.release()
        music = null
        SessionTokens.release()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    companion object {
        /** CVTE's Source panel (HDMI 1, HDMI 2, AV...). */
        const val SHOW_INPUT = "com.cvte.intent.SHOW_INPUT"
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
