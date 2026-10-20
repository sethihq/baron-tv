package com.barontv.remote.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvState

/**
 * The Remote tab as a whole screen, on black, with the TV's name and status over it: what RemoteApp
 * showed before the app had tabs. It's kept only so RemoteApp works until the app shell draws the shared
 * header and puts [RemoteTab] under it; then this can go. With no bridge here, Volume and Mute are key
 * presses.
 */
@Composable
fun RemoteScreen(
    state: TvState,
    onKey: (TvKey) -> Unit,
    onRetry: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduceMotion = rememberReduceMotion()
    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(state, reduceMotion, haptics)
        RemoteControls(state, onKey, onRetry, onOpenWifiSettings, Modifier.weight(1f))
    }
}

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
