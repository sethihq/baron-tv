package com.barontv.remote.ui.playing

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barontv.remote.R
import com.barontv.remote.tv.Volume
import com.barontv.remote.ui.Glyph
import com.barontv.remote.ui.Haptics
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Space
import kotlin.math.roundToInt

/** The slider's row: 44 dp tall, so the thin track is still easy to land on. */
internal val VolumeHeight = 44.dp

/** The track at rest, and while a finger is on it. */
private val Thin = 6.dp
private val Thick = 10.dp

/** A key press moves it five, as the prototype's arrow keys and TalkBack's own steps do. */
private const val KEY_STEP = 5

/**
 * The level a drag sets: where the finger started, plus its travel as a share of the track. Touching the
 * track never makes the volume jump; only moving does.
 */
internal fun dragLevel(start: Int, travelPx: Float, trackPx: Float): Int =
    if (trackPx <= 0f) start else (start + travelPx / trackPx * 100f).roundToInt().coerceIn(0, 100)

/** Whether going from [before] to [after] passed into another tenth, which the slider marks with a tick. */
internal fun crossedTenth(before: Int, after: Int) = before / 10 != after / 10

/**
 * The TV's volume, as the prototype's: a quiet speaker, a thin track with no knob, a loud speaker. A finger
 * anywhere on the row holds it: the track thickens from 6 to 10 dp, and the level follows the finger from
 * where it was, a tick at every tenth. Every move goes to the TV (the model sends at most about eight a
 * second) and the TV shows its own volume bar. Keys and TalkBack move it in fives.
 *
 * Until the TV has said what the volume is, the slider is there but invisible, so nothing below it moves
 * when it appears. Muted, the quiet speaker shows a cross and the fill goes grey.
 */
@Composable
internal fun VolumeSlider(
    volume: Volume?,
    controls: PlayingControls,
    haptics: Haptics,
    width: Dp,
    modifier: Modifier = Modifier,
    previewHeld: Boolean = false,
) {
    var held by remember { mutableStateOf(previewHeld) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    val known = volume != null
    val level = volume?.level ?: 0
    val muted = volume?.muted == true
    val levelNow by rememberUpdatedState(level)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Read in the drawing only, so thickening redraws the track without recomposing the row.
    val thickness = animateDpAsState(if (held) Thick else Thin, tween(200, easing = Motion.EaseOut), label = "track thickness")
    val shown = animateFloatAsState(if (known) 1f else 0f, tween(300, easing = Motion.EaseOut), label = "slider shown")

    fun step(by: Int) {
        val next = (levelNow + by).coerceIn(0, 100)
        if (next == levelNow) return
        if (crossedTenth(levelNow, next)) haptics.slot()
        controls.setVolume(next)
    }

    Row(
        modifier
            .width(width)
            .height(VolumeHeight)
            .graphicsLayer { alpha = shown.value }
            .semantics {
                contentDescription = "TV volume"
                progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), 0f..100f)
                if (muted) stateDescription = "Muted"
                if (known) {
                    setProgress { target ->
                        controls.setVolume(target.roundToInt())
                        true
                    }
                }
            }
            .onKeyEvent { event ->
                if (!known || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.DirectionUp -> step(KEY_STEP)
                    Key.DirectionLeft, Key.DirectionDown -> step(-KEY_STEP)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(enabled = known, interactionSource = interaction)
            .pointerInput(known) {
                if (!known) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    held = true
                    controls.holdVolume()
                    val start = levelNow
                    var last = start
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val next = dragLevel(start, change.position.x - down.position.x, trackPx)
                            if (next != last) {
                                if (crossedTenth(last, next)) haptics.slot()
                                last = next
                                controls.setVolume(next)
                            }
                            change.consume()
                        }
                    } finally {
                        held = false
                        controls.releaseVolume()
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (muted) R.drawable.ph_speaker_simple_x else R.drawable.ph_speaker_simple_low, 18.dp, Palette.Text2)
        Spacer(Modifier.width(Space.S3))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { trackPx = it.width.toFloat() }
                .drawBehind {
                    val h = thickness.value.toPx()
                    val top = (size.height - h) / 2f
                    val radius = CornerRadius(h / 2f)
                    drawRoundRect(Palette.Veil3, Offset(0f, top), Size(size.width, h), radius)
                    // The fill's right end is square, as the prototype's scaled bar inside a rounded track.
                    clipRect(right = size.width * level / 100f) {
                        drawRoundRect(if (muted) Palette.Text3 else Palette.Text, Offset(0f, top), Size(size.width, h), radius)
                    }
                    if (focused) {
                        // The prototype's ring: 2 dp of the accent, 4 dp outside the track.
                        val gap = 4.dp.toPx() + 1.dp.toPx()
                        drawRoundRect(
                            Palette.Ring,
                            Offset(-gap, top - gap),
                            Size(size.width + gap * 2, h + gap * 2),
                            CornerRadius(h / 2f + gap),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                },
        )
        Spacer(Modifier.width(Space.S3))
        Glyph(R.drawable.ph_speaker_simple_high, 18.dp, Palette.Text2)
    }
}
