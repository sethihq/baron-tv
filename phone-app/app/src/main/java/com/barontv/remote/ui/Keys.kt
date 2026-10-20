package com.barontv.remote.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barontv.remote.R
import com.barontv.remote.tv.TvKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The round keys' face: 60 dp, as the prototype's `.key-face`. */
private val Face = 60.dp

/** Volume repeats while held, as the TV's own remote does: first after 420 ms, then every 160 ms. */
private const val HOLD_MS = 420L
private const val REPEAT_MS = 160L

/**
 * The keys the TV's own remote has, under the pad: Back, Home, Input and Menu, then the Volume pill,
 * Mute and Play/Pause, on a grid of four equal columns. The pill spans two columns and its ends line up
 * with the round keys above it. While [enabled] is false a press only plays a gentle reject.
 */
@Composable
internal fun KeyPad(
    enabled: Boolean,
    dim: Float,
    haptics: Haptics,
    onKey: (TvKey) -> Unit,
    rowGap: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.graphicsLayer { alpha = dim },
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        Row(Modifier.fillMaxWidth()) {
            RoundKey(R.drawable.ph_arrow_u_up_left, "Back", TvKey.BACK, enabled, haptics, onKey, Modifier.weight(1f))
            RoundKey(R.drawable.ph_house, "Home", TvKey.HOME, enabled, haptics, onKey, Modifier.weight(1f))
            RoundKey(R.drawable.ph_arrow_square_in, "Input", TvKey.INPUT, enabled, haptics, onKey, Modifier.weight(1f))
            RoundKey(R.drawable.ph_list, "Menu", TvKey.MENU, enabled, haptics, onKey, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            VolumeRocker(enabled, haptics, onKey, Modifier.weight(2f))
            RoundKey(R.drawable.ph_speaker_simple_x, "Mute", TvKey.MUTE, enabled, haptics, onKey, Modifier.weight(1f))
            RoundKey(
                R.drawable.ph_play_pause,
                "Play/Pause",
                TvKey.PLAY_PAUSE,
                enabled,
                haptics,
                onKey,
                Modifier.weight(1f),
                spoken = "Play or pause",
            )
        }
    }
}

/**
 * A round key with its label under it. It sends on release, as a button does, so a finger that slides
 * off sends nothing. Pressed, the face takes a brighter veil and presses in to 0.95 (no ripple, no
 * shadow); every send also dips it once, so even the quickest tap shows.
 */
@Composable
private fun RoundKey(
    @DrawableRes icon: Int,
    label: String,
    key: TvKey,
    enabled: Boolean,
    haptics: Haptics,
    onKey: (TvKey) -> Unit,
    modifier: Modifier = Modifier,
    spoken: String = label,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pulse = remember { Animatable(1f) }
    val down = pressed && enabled
    val press by animateFloatAsState(if (down) 0.95f else 1f, tween(160, easing = Motion.EaseOut), label = "key press")
    val fill by animateColorAsState(
        if (down) Palette.Veil2 else Palette.Veil,
        tween(160, easing = Motion.EaseOut),
        label = "key fill",
    )

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .clickable(interaction, indication = null, role = Role.Button) {
                    if (enabled) {
                        onKey(key)
                        haptics.tap()
                        scope.launch {
                            pulse.snapTo(0.95f)
                            pulse.animateTo(1f, tween(180, easing = Motion.EaseOut))
                        }
                    } else {
                        haptics.reject()
                    }
                }
                .semantics {
                    contentDescription = spoken
                    if (!enabled) disabled()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.S2),
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        // The lower of the two, so a held key coming back up and the release's dip
                        // meet without a jump.
                        val s = minOf(press, pulse.value)
                        scaleX = s
                        scaleY = s
                    }
                    .focusRing(focused, CircleShape)
                    .size(Face)
                    .drawBehind { drawCircle(fill) },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, 24.dp, Palette.Text)
            }
            KeyLabel(label)
        }
    }
}

/**
 * Volume: one pill across two columns, minus on the left and plus on the right. Its ends line up with the
 * round keys above (it is inset by half a column less half a face on each side, as the prototype's
 * `calc(25% - 30px)`).
 */
@Composable
private fun VolumeRocker(enabled: Boolean, haptics: Haptics, onKey: (TvKey) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val inset = (maxWidth / 4 - Face / 2).coerceAtLeast(0.dp)
        Column(
            Modifier.padding(horizontal = inset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.S2),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Face)
                    .clip(CircleShape)
                    .background(Palette.Veil),
            ) {
                RockerHalf(
                    R.drawable.ph_minus,
                    "Volume down",
                    TvKey.VOLUME_DOWN,
                    RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                    enabled,
                    haptics,
                    onKey,
                    Modifier.weight(1f),
                )
                RockerHalf(
                    R.drawable.ph_plus,
                    "Volume up",
                    TvKey.VOLUME_UP,
                    RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                    enabled,
                    haptics,
                    onKey,
                    Modifier.weight(1f),
                )
            }
            KeyLabel("Volume")
        }
    }
}

/**
 * One half of the volume pill. It sends as soon as it's pressed, then again after 420 ms and every
 * 160 ms while the finger stays on it; lifting the finger or sliding off stops it. Each repeat ticks
 * lighter than the first press. A keyboard, switch or TalkBack press sends one step.
 */
@Composable
private fun RockerHalf(
    @DrawableRes icon: Int,
    spoken: String,
    key: TvKey,
    shape: RoundedCornerShape,
    enabled: Boolean,
    haptics: Haptics,
    onKey: (TvKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val enabledNow by rememberUpdatedState(enabled)
    val onKeyNow by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(1f) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, tween(160, easing = Motion.EaseOut), label = "rocker press")
    val fill by animateColorAsState(
        if (pressed) Palette.Veil2 else Color.Transparent,
        tween(160, easing = Motion.EaseOut),
        label = "rocker fill",
    )

    fun send(first: Boolean) {
        onKeyNow(key)
        if (first) haptics.tap() else haptics.repeat()
        scope.launch {
            pulse.snapTo(if (first) 0.95f else 0.97f)
            pulse.animateTo(1f, tween(180, easing = Motion.EaseOut))
        }
    }

    Box(
        modifier
            .fillMaxHeight()
            .focusRing(focused, shape, offset = (-2).dp)
            // The pill around it is already clipped round, so a plain fill takes its shape.
            .drawBehind { drawRect(fill) }
            .semantics {
                role = Role.Button
                contentDescription = spoken
                if (enabled) {
                    onClick {
                        send(first = true)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key !in ACTIVATE_KEYS) return@onKeyEvent false
                if (enabledNow) send(first = true) else haptics.reject()
                true
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!enabledNow) {
                        haptics.reject()
                        return@awaitEachGesture
                    }
                    pressed = true
                    send(first = true)
                    val repeating: Job = scope.launch {
                        delay(HOLD_MS)
                        while (true) {
                            delay(REPEAT_MS)
                            if (!enabledNow) break
                            send(first = false)
                        }
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isOutOfBounds(size, extendedTouchPadding)) break
                            change.consume()
                        }
                    } finally {
                        repeating.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            icon,
            22.dp,
            Palette.Text,
            Modifier.graphicsLayer {
                // Held, the glyph sits pressed in at 0.9 and each repeat dips it a little further.
                val s = press * pulse.value
                scaleX = s
                scaleY = s
            },
        )
    }
}

/** The caption under a key, in the quieter grey. TalkBack reads the key's own description instead. */
@Composable
private fun KeyLabel(text: String) {
    BasicText(
        text = text,
        style = Type.Caption.copy(color = Palette.Text2, textAlign = TextAlign.Center),
        modifier = Modifier.clearAndSetSemantics {},
    )
}

private val ACTIVATE_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)
