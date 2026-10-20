package com.barontv.remote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barontv.remote.tv.TvState
import kotlinx.coroutines.delay

/** What the screen calls the TV before it has found one. */
private const val FALLBACK_NAME = "Baron TV"

/** How long a tap on the name shows the TV's address in place of "Connected". */
private const val ADDRESS_MS = 2400L

private val HeaderShape = RoundedCornerShape(16.dp)

/**
 * Which TV, and whether the phone can reach it: the TV's name over a status line with a dot, centred,
 * as the prototype's header. It sits over every tab. The four states stay calm and keep the same shape,
 * so nothing jumps:
 *
 * - Connected: a green dot and "Connected". A tap on the name shows the TV's address there for a
 *   moment (its address changes at every boot, which is worth seeing now and then).
 * - Searching: "Looking for the TV", with a grey dot that softly pulses.
 * - NotFound and NoWifi: "TV not found" and "Not on Wi-Fi", with a still grey dot. The Remote tab's pad
 *   says what to do about it, with a button; from any tab a tap here does the same: [onRetry] looks for
 *   the TV again, [onOpenWifiSettings] opens the phone's Wi-Fi settings.
 *
 * The prototype's chevron is gone: it promised a TV picker, and there's only ever one TV.
 */
@Composable
internal fun Header(
    state: TvState,
    reduceMotion: Boolean,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onOpenWifiSettings: (() -> Unit)? = null,
) {
    val connected = state as? TvState.Connected
    // Each tap restarts the moment, so a second tap keeps the address up.
    var taps by remember { mutableIntStateOf(0) }
    var revealing by remember { mutableStateOf(false) }
    LaunchedEffect(taps) {
        if (taps > 0) {
            revealing = true
            delay(ADDRESS_MS)
            revealing = false
        }
    }
    val showAddress = connected != null && revealing
    // What a tap does in each state, and what TalkBack calls it.
    val tap: Pair<String, () -> Unit>? = when (state) {
        is TvState.Connected -> "show its address" to { taps++ }
        TvState.NotFound -> onRetry?.let { "look for it again" to it }
        TvState.NoWifi -> onOpenWifiSettings?.let { "open Wi-Fi settings" to it }
        TvState.Searching -> null
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (pressed && tap != null) Palette.Veil else Color.Transparent,
        tween(160, easing = Motion.EaseOut),
        label = "header fill",
    )

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Edge)
            .padding(top = Space.S1, bottom = Space.S3),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .focusRing(focused, HeaderShape)
                .clip(HeaderShape)
                .background(fill)
                .then(
                    if (tap != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = tap.first,
                        ) {
                            haptics.tap()
                            tap.second()
                        }
                    } else {
                        Modifier
                    },
                )
                // One stop for TalkBack, read again whenever the state changes.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.S4)
                .padding(top = 2.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = connected?.name ?: FALLBACK_NAME,
                transitionSpec = { crossFade() },
                label = "tv name",
            ) { name ->
                BasicText(name, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            StatusLine(state, showAddress, reduceMotion)
        }
    }
}

private enum class Dot { Live, Looking, Off }

private data class Status(val text: String, val dot: Dot)

private fun statusOf(state: TvState, showAddress: Boolean): Status = when (state) {
    is TvState.Connected -> Status(if (showAddress) state.host else "Connected", Dot.Live)
    TvState.Searching -> Status("Looking for the TV", Dot.Looking)
    TvState.NotFound -> Status("TV not found", Dot.Off)
    TvState.NoWifi -> Status("Not on Wi-Fi", Dot.Off)
}

/** The dot and the words after it, in the caption style. Changes cross-fade. */
@Composable
private fun StatusLine(state: TvState, showAddress: Boolean, reduceMotion: Boolean) {
    val status = statusOf(state, showAddress)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status.dot, reduceMotion)
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = status.text,
            transitionSpec = { crossFade() },
            label = "tv status",
        ) { text ->
            // Tabular figures, so an address reads as a steady column of digits.
            BasicText(
                text,
                style = Type.Caption.copy(color = Palette.Text2, fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Green while connected; grey while looking, breathing between full and a third over a second each
 * way, like the TV's "Connecting" badge; a quieter grey, still, when there's no TV.
 */
@Composable
private fun StatusDot(dot: Dot, reduceMotion: Boolean) {
    val colour by animateColorAsState(
        when (dot) {
            Dot.Live -> Palette.Live
            Dot.Looking -> Palette.Text2
            Dot.Off -> Palette.Text3
        },
        tween(300, easing = Motion.EaseOut),
        label = "status dot",
    )
    // Only runs while looking; read in the layer, so the breathing never recomposes the header.
    val breath: State<Float>? = if (dot == Dot.Looking && !reduceMotion) {
        rememberInfiniteTransition(label = "looking").animateFloat(
            initialValue = 1f,
            targetValue = 0.32f,
            animationSpec = infiniteRepeatable(tween(1000, easing = Motion.EaseOut), RepeatMode.Reverse),
            label = "looking dot",
        )
    } else {
        null
    }
    Box(
        Modifier
            .size(6.dp)
            .graphicsLayer { alpha = breath?.value ?: 1f }
            .background(colour, CircleShape),
    )
}

/** The new words fade in as the old ones fade out, and the line eases to its new width. */
private fun <S> AnimatedContentTransitionScope<S>.crossFade(): ContentTransform =
    fadeIn(tween(200, easing = Motion.EaseOut)) togetherWith fadeOut(tween(120)) using
        SizeTransform(clip = false) { _, _ -> tween(200, easing = Motion.EaseOut) }
