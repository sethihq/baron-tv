package com.barontv.remote.ui.playing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.barontv.remote.tv.TvBridge

/**
 * The Playing tab: what's playing on the TV, with previous, play or pause, next, and the TV's volume.
 * It sits under the app's shared header and above its tab bar, and asks the TV nothing while it's off
 * screen or the app is in the background.
 */
@Composable
fun PlayingTab(bridge: TvBridge, modifier: Modifier = Modifier) {
    val model = remember(bridge) { modelFor(bridge) }
    PlayingContent(model, modifier)
}

/**
 * The tab for [model]: it polls while this is composed and the app is at least started, and shows the
 * model's state. [previewHeld] shows the volume slider as if a finger were on it, for snapshots.
 */
@Composable
internal fun PlayingContent(model: PlayingModel, modifier: Modifier = Modifier, previewHeld: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.run() }
    }
    val ui by model.state.collectAsState()
    PlayingScreen(ui, model, modifier, previewHeld)
}

// The model outlives the tab, so coming back to it shows the last song at once (the next poll updates
// it) instead of an empty page while the first answer comes back. One per bridge: the app has one.
private var kept: PlayingModel? = null

private fun modelFor(bridge: TvBridge): PlayingModel =
    kept?.takeIf { it.bridge === bridge } ?: PlayingModel(bridge).also { kept = it }
