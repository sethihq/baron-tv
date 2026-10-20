package com.barontv.remote.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvState

/** How far the pad and keys fade while there's no TV: still readable, plainly asleep. */
private const val DIMMED = 0.45f

/**
 * The Remote screen, edge to edge on black: the TV's name and status at the top, the touch pad taking
 * whatever height is left, and the keys of the TV's own remote under it, near the thumb.
 *
 * Stateless: it shows [state] and reports presses through [onKey], [onRetry] and [onOpenWifiSettings].
 * While the TV isn't connected the pad and keys dim and do nothing but play a gentle reject, and the pad
 * says what to do instead.
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
    val connected = state is TvState.Connected
    val dim by animateFloatAsState(if (connected) 1f else DIMMED, tween(300, easing = Motion.EaseOut), label = "dim")
    val notice = noticeFor(state)

    val pad = @Composable { padModifier: Modifier ->
        TouchPad(
            enabled = connected,
            dim = dim,
            reduceMotion = reduceMotion,
            haptics = haptics,
            onKey = onKey,
            modifier = padModifier,
            showHint = notice == null,
        ) {
            AnimatedContent(
                targetState = notice,
                transitionSpec = { fadeIn(tween(300, easing = Motion.EaseOut)) togetherWith fadeOut(tween(150)) },
                label = "pad notice",
            ) { shown ->
                if (shown != null) {
                    PadNotice(
                        notice = shown,
                        onAction = {
                            haptics.tap()
                            if (shown == Notice.NotFound) onRetry() else onOpenWifiSettings()
                        },
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val rhythm = Rhythm.forHeight(maxHeight)
        val sideBySide = maxWidth > maxHeight && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH

        if (sideBySide) {
            // Landscape: the pad on the left, the keys beside it, both under the header.
            Column(Modifier.fillMaxSize()) {
                Header(state, reduceMotion, haptics)
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Space.Edge)
                        .padding(top = rhythm.top, bottom = Space.S5),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pad(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(Space.S8))
                    KeyPad(connected, dim, haptics, onKey, rhythm.rowGap, Modifier.width(KEYS_WIDTH_BESIDE))
                }
            }
        } else {
            // Portrait. A window too short for the pad (split screen) scrolls instead of squeezing it.
            val short = maxHeight < SHORT_BELOW
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(state, reduceMotion, haptics)
                Column(
                    Modifier
                        .then(if (short) Modifier else Modifier.weight(1f))
                        .widthIn(max = MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(horizontal = Space.Edge),
                ) {
                    Spacer(Modifier.height(rhythm.top))
                    pad(
                        Modifier
                            .fillMaxWidth()
                            .then(if (short) Modifier.height(PAD_MIN) else Modifier.weight(1f)),
                    )
                    Spacer(Modifier.height(rhythm.padToKeys))
                    KeyPad(connected, dim, haptics, onKey, rhythm.rowGap, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(rhythm.bottom))
                }
            }
        }
    }
}

/**
 * The vertical spacing. On a tall phone it is the prototype's: 12 above the pad, 32 between the pad and
 * the keys, 24 between the two rows of keys, and 32 below them. A short phone (a Nexus 5, say) tightens
 * the gaps so the pad keeps a usable height instead.
 */
private class Rhythm(val top: Dp, val padToKeys: Dp, val rowGap: Dp, val bottom: Dp) {
    companion object {
        private val Tall = Rhythm(top = Space.S3, padToKeys = Space.S8, rowGap = Space.S6, bottom = Space.S8)
        private val Compact = Rhythm(top = Space.S2, padToKeys = Space.S6, rowGap = Space.S4, bottom = Space.S5)

        fun forHeight(height: Dp) = if (height < COMPACT_BELOW) Compact else Tall
    }
}

private val COMPACT_BELOW = 700.dp
private val SHORT_BELOW = 460.dp
private val PAD_MIN = 200.dp
private val MAX_WIDTH = 480.dp
private val SIDE_BY_SIDE_MIN_WIDTH = 600.dp
private val KEYS_WIDTH_BESIDE = 340.dp

/** What the pad says while there's no TV, and the one thing to do about it. */
private enum class Notice(val text: String, val action: String) {
    NotFound("Turn the TV on and keep this phone on the same Wi-Fi.", "Try again"),
    NoWifi("Join the TV's Wi-Fi to use the remote.", "Wi-Fi settings"),
}

private fun noticeFor(state: TvState): Notice? = when (state) {
    TvState.NotFound -> Notice.NotFound
    TvState.NoWifi -> Notice.NoWifi
    else -> null
}

/** One short line in the quieter grey, balanced over two lines when it wraps, and a pill under it. */
@Composable
private fun PadNotice(notice: Notice, onAction: () -> Unit) {
    Column(
        Modifier
            .padding(Space.S6)
            .widthIn(max = 280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.S4),
    ) {
        BasicText(
            text = notice.text,
            style = Type.Body.copy(color = Palette.Text2, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading),
        )
        Pill(notice.action, onClick = onAction)
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
