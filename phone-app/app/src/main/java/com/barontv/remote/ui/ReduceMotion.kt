package com.barontv.remote.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the phone asks for less motion (Remove animations, which sets the animator scale to 0). Then,
 * as the prototype does with reduced motion, nothing nudges or grows: carets and rings only fade, and
 * the searching dot holds still.
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
