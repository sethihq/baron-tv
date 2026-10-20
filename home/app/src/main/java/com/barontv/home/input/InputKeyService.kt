package com.barontv.home.input

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.barontv.home.media.SessionTokens
import com.barontv.home.music.OverlayMusic

/**
 * The remote's Input key opens CVTE's source panel, from any app. CVTE remotes send KEYCODE_TV_INPUT
 * (scan code 79), which the firmware only ever answered with a broadcast to the stock launcher, blocked
 * on Android 9; this service catches it instead. Every other key passes through untouched. Moved here
 * from the old launcher's InputKeyService, which it replaces (one of the two is enabled at a time).
 *
 * It also sees every notification posted, which is how the home screen learns what's playing on this TV:
 * low-RAM Android has no notification access, but a music app's notification carries its media session's
 * token (see SessionTokens). And it hosts music mode over the music app in front (OverlayMusic): it sees
 * every key, so it knows when the remote rests, and which app is in front.
 */
class InputKeyService : AccessibilityService() {
    private var music: OverlayMusic? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        music = OverlayMusic(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (music?.onKey(event) == true) return true
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_TV_INPUT && code != KeyEvent.KEYCODE_TV) return false
        if (event.action == KeyEvent.ACTION_UP) {
            try {
                startActivity(Intent(SHOW_INPUT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: ActivityNotFoundException) {
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> music?.onFront(pkg)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notification = event.parcelableData as? Notification ?: return
                SessionTokens.onNotification(this, pkg, notification)
            }
        }
    }

    override fun onDestroy() {
        music?.release()
        music = null
        SessionTokens.release()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    companion object {
        /** CVTE's Source panel (HDMI 1, HDMI 2, AV...). */
        const val SHOW_INPUT = "com.cvte.intent.SHOW_INPUT"
    }
}
