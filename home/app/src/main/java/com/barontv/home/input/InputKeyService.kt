package com.barontv.home.input

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's Input key opens CVTE's source panel, from any app. CVTE remotes send KEYCODE_TV_INPUT
 * (scan code 79), which the firmware only ever answered with a broadcast to the stock launcher, blocked
 * on Android 9; this service catches it instead. Every other key passes through untouched. Moved here
 * from the old launcher's InputKeyService, which it replaces (one of the two is enabled at a time).
 */
class InputKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        /** CVTE's Source panel (HDMI 1, HDMI 2, AV...). */
        const val SHOW_INPUT = "com.cvte.intent.SHOW_INPUT"
    }
}
