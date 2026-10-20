package com.barontv.remote.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barontv.remote.tv.TvRemote

/** The whole app for now: the Remote screen, showing whatever the TV connection reports. */
@Composable
fun RemoteApp(remote: TvRemote) {
    val state by remote.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LightBarsOnBlack()
    RemoteScreen(
        state = state,
        onKey = remote::send,
        onRetry = remote::retry,
        onOpenWifiSettings = { openWifiSettings(context) },
    )
}

/**
 * The screen is always black, so the status and navigation bars always want light icons and no scrim.
 * `enableEdgeToEdge()` picks them from the phone's light or dark mode, which in light mode would put dark
 * icons, and a white bar behind three-button navigation, over the black.
 */
@Composable
private fun LightBarsOnBlack() {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

/**
 * Opens the phone's Wi-Fi settings: the small panel over the app where the phone has it (Android 10 and
 * later), the full settings page before that.
 */
private fun openWifiSettings(context: Context) {
    val newTask = context.findActivity() == null
    val choices = buildList {
        if (Build.VERSION.SDK_INT >= 29) add(Intent(Settings.Panel.ACTION_WIFI))
        add(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    for (intent in choices) {
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // A trimmed-down phone without this screen: try the next one.
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
