package com.barontv.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.barontv.remote.R
import com.barontv.remote.tv.TvKey
import kotlinx.coroutines.launch

private val PadShape = SquircleShape(32.dp)

/** How long a step's caret and a tap's ring take to settle: the prototype's 420 ms. */
private const val FLASH_MS = 420

/**
 * The touch pad. A swipe moves the TV's focus one step for every 56 dp of travel, a flick moves it once
 * and a tap is OK (the rules are in [PadGesture]). Four faint carets say it has directions and the one a
 * step went lights up; a soft light follows the finger; a thin ring opens where a tap lands. The hint in
 * the middle fades for good at the first touch.
 *
 * While [enabled] is false (no TV) a touch does nothing but play a gentle reject, once. The surface is
 * drawn at [dim]; the [overlay] (what to do about the missing TV) sits centred on top, undimmed.
 */
@Composable
internal fun TouchPad(
    enabled: Boolean,
    dim: Float,
    reduceMotion: Boolean,
    haptics: Haptics,
    onKey: (TvKey) -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val enabledNow by rememberUpdatedState(enabled)
    val onKeyNow by rememberUpdatedState(onKey)

    var hintGone by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Unspecified) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val light = remember { Animatable(0f) }
    val cues = remember { CUES.associateWith { Animatable(1f) } }
    val rings = remember { mutableStateListOf<TapRing>() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val surface by animateColorAsState(
        if (pressed) Palette.PadPressed else Palette.Veil,
        tween(200, easing = Motion.EaseOut),
        label = "pad surface",
    )
    val hintAlpha by animateFloatAsState(
        if (hintGone || !showHint) 0f else 1f,
        tween(300, easing = Motion.EaseOut),
        label = "pad hint",
    )

    // Sends a key from the pad. A step flashes its caret and ticks; OK taps and opens a ring where it
    // landed (the middle, for a keyboard or TalkBack).
    fun press(key: TvKey, at: Offset) {
        hintGone = true
        onKeyNow(key)
        if (key == TvKey.OK) {
            haptics.tap()
            val centre = Offset(padSize.width / 2f, padSize.height / 2f)
            val ring = TapRing(if (at.isSpecified) at else centre)
            rings += ring
            scope.launch {
                ring.progress.animateTo(1f, tween(FLASH_MS, easing = Motion.EaseOut))
                rings -= ring
            }
        } else {
            haptics.tick()
            val cue = cues.getValue(key)
            scope.launch {
                cue.snapTo(0f)
                cue.animateTo(1f, tween(FLASH_MS, easing = Motion.EaseOut))
            }
        }
    }

    Box(modifier.onSizeChanged { padSize = it }) {
        // What shows: the surface, the carets and the hint, all dimmed together while there's no TV.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = dim }
                .drawWithCache {
                    val path = (PadShape.createOutline(size, layoutDirection, this) as Outline.Generic).path
                    val lightRadius = 66.dp.toPx()
                    val ringRadius = 46.dp.toPx()
                    val ringWidth = 1.dp.toPx()
                    // Drawn twice as wide and clipped to the pad, so exactly 1.5 dp shows inside the edge.
                    val focusStroke = Stroke(1.5.dp.toPx() * 2)
                    onDrawBehind {
                        drawPath(path, surface)
                        clipPath(path) {
                            // The prototype's `.touch`: a soft light under the finger, glued to it.
                            if (light.value > 0f && finger.isSpecified) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        0f to Color.White.copy(alpha = 0.09f),
                                        0.55f to Color.White.copy(alpha = 0.03f),
                                        1f to Color.White.copy(alpha = 0f),
                                        center = finger,
                                        radius = lightRadius,
                                    ),
                                    radius = lightRadius,
                                    center = finger,
                                    alpha = light.value,
                                )
                            }
                            // `.ripple`: a 1 dp ring of 40% white that opens from 0.45 to full size while
                            // it fades from half strength. With reduced motion it only fades.
                            for (ring in rings) {
                                val p = ring.progress.value
                                val scale = if (reduceMotion) 1f else lerp(0.45f, 1f, p)
                                val strength = lerp(if (reduceMotion) 0.45f else 0.5f, 0f, p)
                                val width = ringWidth * scale
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.4f * strength),
                                    radius = ringRadius * scale - width / 2f,
                                    center = ring.at,
                                    style = Stroke(width),
                                )
                            }
                            // Keyboard focus, as `.pad:focus-visible`: an inset ring in the quiet accent.
                            if (focused) drawPath(path, Palette.Ring, style = focusStroke)
                        }
                    }
                },
        ) {
            for (key in CUES) {
                val cue = cues.getValue(key)
                Cue(key, settle = { cue.value }, reduceMotion)
            }
            BasicText(
                text = "Swipe to move\nTap to select",
                style = Type.Body.copy(color = Palette.Text3, textAlign = TextAlign.Center, lineHeight = 24.sp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = hintAlpha }
                    .clearAndSetSemantics {},
            )
        }

        // What reacts. TalkBack users get the five keys as actions and a double tap is OK; a hardware
        // keyboard's arrows and Enter work too, as in the prototype. The pad is kept out of Android's
        // back gesture, so a swipe that starts near the screen's edge stays a swipe.
        Box(
            Modifier
                .matchParentSize()
                .systemGestureExclusion()
                .semantics {
                    contentDescription = "Touch pad"
                    if (enabled) {
                        onClick(label = "select") {
                            press(TvKey.OK, Offset.Unspecified)
                            true
                        }
                        customActions = PAD_KEYS.map { key ->
                            CustomAccessibilityAction(ACTION_LABELS.getValue(key)) {
                                press(key, Offset.Unspecified)
                                true
                            }
                        }
                    } else {
                        disabled()
                    }
                }
                .onKeyEvent { event ->
                    val key = KEYBOARD[event.key]
                    if (event.type != KeyEventType.KeyDown || key == null) return@onKeyEvent false
                    if (enabledNow) press(key, Offset.Unspecified) else haptics.reject()
                    true
                }
                .focusable(interactionSource = interaction)
                .pointerInput(Unit) {
                    val gesture = PadGesture(
                        slop = PadGesture.SLOP_DP.dp.toPx(),
                        step = PadGesture.STEP_DP.dp.toPx(),
                        flick = PadGesture.FLICK_DP_PER_MS * density,
                    )
                    awaitEachGesture {
                        // One finger drives the pad; a second one changes nothing.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val live = enabledNow
                        var rejected = false
                        fun act(key: TvKey?, at: Offset) {
                            when {
                                key == null -> Unit
                                live && enabledNow -> press(key, at)
                                !rejected -> {
                                    rejected = true
                                    haptics.reject()
                                }
                            }
                        }
                        gesture.down(down.position.x, down.position.y, down.uptimeMillis)
                        if (live) {
                            hintGone = true
                            pressed = true
                            finger = down.position
                            scope.launch { light.animateTo(1f, tween(60, easing = Motion.EaseOut)) }
                        }
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    gesture.cancel()
                                    break
                                }
                                if (!change.pressed) {
                                    // A lift that arrives already consumed means the system took the touch.
                                    if (change.isConsumed) {
                                        gesture.cancel()
                                    } else {
                                        val p = change.position
                                        act(gesture.up(p.x, p.y, change.uptimeMillis), p)
                                    }
                                    break
                                }
                                if (change.positionChanged()) {
                                    val p = change.position
                                    if (live) finger = p
                                    act(gesture.move(p.x, p.y, change.uptimeMillis), p)
                                    change.consume()
                                }
                            }
                        } finally {
                            pressed = false
                            scope.launch { light.animateTo(0f, tween(220, easing = Motion.EaseOut)) }
                        }
                    }
                },
        )

        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = overlay)
    }
}

/**
 * One of the four carets, 12 dp in from its edge. [settle] runs 0 to 1 after a step: bright, then back.
 * It's read in the layer, so a flash redraws the caret without recomposing the pad.
 */
@Composable
private fun BoxScope.Cue(key: TvKey, settle: () -> Float, reduceMotion: Boolean) {
    val look = CUE_LOOK.getValue(key)
    Box(
        Modifier
            .align(look.alignment)
            .padding(Space.S3)
            .size(28.dp)
            .graphicsLayer {
                val p = settle()
                alpha = lerp(0.7f, 0.28f, p)
                if (!reduceMotion) {
                    // Nudged 4 dp outward at the flash, settling back in.
                    val nudge = 4.dp.toPx() * (1f - p)
                    translationX = look.dx * nudge
                    translationY = look.dy * nudge
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(look.icon, 18.dp, Palette.Text)
    }
}

private class CueLook(val alignment: Alignment, val icon: Int, val dx: Float, val dy: Float)

private val CUE_LOOK = mapOf(
    TvKey.UP to CueLook(Alignment.TopCenter, R.drawable.ph_caret_up, 0f, -1f),
    TvKey.DOWN to CueLook(Alignment.BottomCenter, R.drawable.ph_caret_down, 0f, 1f),
    TvKey.LEFT to CueLook(Alignment.CenterStart, R.drawable.ph_caret_left, -1f, 0f),
    TvKey.RIGHT to CueLook(Alignment.CenterEnd, R.drawable.ph_caret_right, 1f, 0f),
)

private class TapRing(val at: Offset) {
    val progress = Animatable(0f)
}

private val CUES = listOf(TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT)

private val PAD_KEYS = CUES + TvKey.OK

private val ACTION_LABELS = mapOf(
    TvKey.UP to "Up",
    TvKey.DOWN to "Down",
    TvKey.LEFT to "Left",
    TvKey.RIGHT to "Right",
    TvKey.OK to "OK",
)

private val KEYBOARD = mapOf(
    Key.DirectionUp to TvKey.UP,
    Key.DirectionDown to TvKey.DOWN,
    Key.DirectionLeft to TvKey.LEFT,
    Key.DirectionRight to TvKey.RIGHT,
    Key.Enter to TvKey.OK,
    Key.NumPadEnter to TvKey.OK,
    Key.DirectionCenter to TvKey.OK,
    Key.Spacebar to TvKey.OK,
)
