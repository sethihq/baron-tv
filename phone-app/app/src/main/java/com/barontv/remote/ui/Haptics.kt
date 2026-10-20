package com.barontv.remote.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The prototype's named haptic patterns (phone.js, `HAPTICS`), one call each. The lighter the action, the
 * lighter the feel, and only an action that commits something gets more than one pulse.
 *
 * Everything goes through [View.performHapticFeedback], so it needs no permission and follows the
 * phone's own touch feedback setting. Each pattern uses a constant that exists on API 26, and a better
 * one where the phone has it:
 *
 * | Pattern | Used for                         | Prototype (ms)     | API 26 and up  | Better, from          |
 * |---------|----------------------------------|--------------------|----------------|-----------------------|
 * | tick    | a D-pad step from the touch pad  | 6                  | CLOCK_TICK     |                       |
 * | tap     | OK and the keys                  | 10                 | VIRTUAL_KEY    |                       |
 * | repeat  | each volume step while held      | 5                  | CLOCK_TICK     | SEGMENT_TICK, 34      |
 * | confirm | an app opening on the TV         | 12, 60 off, 18     | VIRTUAL_KEY    | CONFIRM, 30           |
 * | reject  | a press while there's no TV      | 8, 50 off, 8 ...   | two CLOCK_TICK | REJECT, 30            |
 * | lift    | picking up a dock row            | 14                 | LONG_PRESS     | DRAG_START, 34        |
 * | slot    | a dragged row passing a slot     | 4                  | CLOCK_TICK     | SEGMENT_TICK, 34      |
 * | select  | a tab or a picker choice         | 7                  | CLOCK_TICK     | SEGMENT_FREQUENT_TICK, 34 |
 *
 * Repeat is a tick rather than the KEYBOARD_TAP the prototype's notes suggest: Android plays KEYBOARD_TAP
 * as the same click as VIRTUAL_KEY, and a held volume key should feel lighter than a press.
 */
class Haptics(private val view: View) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun repeat() = perform(if (atLeast(34)) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun confirm() = perform(if (atLeast(30)) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /**
     * A gentle "no": REJECT's double pulse where the phone has it. Before API 30, two light ticks 60 ms
     * apart stand in, close to the prototype's three short pulses.
     */
    fun reject() {
        if (atLeast(30)) {
            perform(HapticFeedbackConstants.REJECT)
        } else {
            perform(HapticFeedbackConstants.CLOCK_TICK)
            view.postDelayed({ perform(HapticFeedbackConstants.CLOCK_TICK) }, 60)
        }
    }

    fun lift() = perform(if (atLeast(34)) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS)

    fun slot() = perform(if (atLeast(34)) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun select() =
        perform(if (atLeast(34)) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    @ChecksSdkIntAtLeast(parameter = 0)
    private fun atLeast(api: Int) = Build.VERSION.SDK_INT >= api
}

/** The screen's [Haptics], played through the view that hosts the composition. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
