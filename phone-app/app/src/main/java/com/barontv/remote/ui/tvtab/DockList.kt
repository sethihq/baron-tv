package com.barontv.remote.ui.tvtab

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.barontv.remote.R
import com.barontv.remote.tv.TvApp
import com.barontv.remote.ui.Glyph
import com.barontv.remote.ui.Haptics
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.Type
import com.barontv.remote.ui.apps.AppCover
import com.barontv.remote.ui.apps.ordinal
import com.barontv.remote.ui.focusRing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A row's height in dp, as the prototype's `.dock-row` (`ROW` in phone.js). */
internal const val ROW_DP = 56f

/** Where row [index] sits while the row at [from] is dragged towards [to]: the rows between make room. */
internal fun slotOf(index: Int, from: Int, to: Int): Int = when {
    from < to && index in (from + 1)..to -> index - 1
    from > to && index in to until from -> index + 1
    else -> index
}

/** Past the first or last place a row still follows the finger, with more and more resistance (dp). */
internal fun rubberBand(over: Float): Float = over * 140f * 0.55f / (140f + 0.55f * over)

/**
 * The drag in the dock list, in dp: which row is lifted, the slot it came from, how far it has moved
 * and the slot it would drop into. One row at a time.
 */
@Stable
internal class DockDrag {
    /** The lifted app's package, or null while nothing is lifted. */
    var key: String? by mutableStateOf(null)
        private set
    var from by mutableIntStateOf(0)
        private set
    var to by mutableIntStateOf(0)
        private set

    /** How far the lifted row is from its own slot, in dp: the finger's travel, rubber-banded past the ends. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** From the drop until the row has glided into its slot. */
    var settling by mutableStateOf(false)
        private set

    private var travel = 0f

    val active: Boolean get() = key != null

    fun lift(key: String, index: Int) {
        this.key = key
        from = index
        to = index
        offset = 0f
        travel = 0f
        settling = false
    }

    /** The finger moved [dy] dp in a list of [count] rows. True when the row now points at a new slot. */
    fun moveBy(dy: Float, count: Int): Boolean {
        if (key == null || settling) return false
        travel += dy
        val min = -from * ROW_DP
        val max = (count - 1 - from) * ROW_DP
        offset = when {
            travel < min -> min - rubberBand(min - travel)
            travel > max -> max + rubberBand(travel - max)
            else -> travel
        }
        val slot = (from + offset / ROW_DP).roundToInt().coerceIn(0, count - 1)
        if (slot == to) return false
        to = slot
        return true
    }

    /** Glides the lifted row into the slot it was dropped on. */
    suspend fun settle(spec: AnimationSpec<Float>) {
        settling = true
        animate(offset, (to - from) * ROW_DP, animationSpec = spec) { value, _ -> offset = value }
    }

    fun clear() {
        key = null
        offset = 0f
        travel = 0f
        settling = false
    }
}

/** oklch(22% 0 none): a lifted row, a shade up from the card it floats over. */
private val LiftedFill = Color(0xFF1B1B1B)

private val RowShape = RoundedCornerShape(14.dp)
private val ThumbShape = RoundedCornerShape(8.dp)
private val HandleShape = RoundedCornerShape(12.dp)

/**
 * The dock's apps in order, each a row of its cover, its name and a drag handle, as the prototype's
 * `.dock-list`. Pressing a handle lifts its row (a shade lighter, a soft shadow, a touch bigger) and it
 * follows the finger with no lag; the rows it passes make room, with a "slot" tick each time. Let go and
 * it glides into place, then [onMove] gets the app and its new place. A focused handle moves its row a
 * place with the Up and Down keys; TalkBack gets "Move up" and "Move down" on every row.
 *
 * Every row is placed by its slot rather than by the order of the list, so when the new order arrives
 * each row is already where it belongs and nothing jumps.
 */
@Composable
internal fun DockList(
    apps: List<TvApp>,
    onMove: (packageName: String, to: Int) -> Unit,
    haptics: Haptics,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    drag: DockDrag = remember { DockDrag() },
) {
    val scope = rememberCoroutineScope()
    val move by rememberUpdatedState(onMove)
    val count = apps.size

    fun drop() {
        scope.launch {
            haptics.tap()
            drag.settle(if (reduceMotion) snap() else tween(200, easing = Motion.EaseOut))
            // The new order and the end of the drag land in the same frame.
            val lifted = drag.key
            if (lifted != null && drag.from != drag.to) move(lifted, drag.to)
            drag.clear()
        }
    }

    fun step(app: TvApp, index: Int, by: Int) {
        val to = index + by
        if (drag.active || to !in 0 until count) return
        haptics.slot()
        move(app.packageName, to)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height((ROW_DP * count).dp),
    ) {
        apps.forEachIndexed { index, app ->
            key(app.packageName) {
                DockRow(
                    app = app,
                    index = index,
                    count = count,
                    drag = drag,
                    reduceMotion = reduceMotion,
                    onLift = haptics::lift,
                    onSlot = haptics::slot,
                    onDrop = ::drop,
                    onStep = { by -> step(app, index, by) },
                )
            }
        }
    }
}

@Composable
private fun DockRow(
    app: TvApp,
    index: Int,
    count: Int,
    drag: DockDrag,
    reduceMotion: Boolean,
    onLift: () -> Unit,
    onSlot: () -> Unit,
    onDrop: () -> Unit,
    onStep: (Int) -> Unit,
) {
    val dragged = drag.key == app.packageName
    val active = drag.active
    val slot = if (active && !dragged) slotOf(index, drag.from, drag.to) else index
    val glide = if (reduceMotion) snap<Float>() else tween(200, easing = Motion.EaseOut)
    // The lifted row follows the finger exactly; the others glide to make room.
    val y by animateFloatAsState(
        targetValue = if (dragged) drag.from * ROW_DP + drag.offset else slot * ROW_DP,
        animationSpec = if (dragged) snap() else glide,
        label = "dock row",
    )
    val lifted by animateFloatAsState(if (dragged && !drag.settling) 1f else 0f, glide, label = "dock lift")
    // A hairline between rows, starting under the names. None touches the lifted row or the gap it leaves.
    val showLine = slot > 0 && !dragged && !(active && slot == drag.to + 1)
    val line by animateFloatAsState(if (showLine) 1f else 0f, tween(150), label = "dock line")
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val lift by rememberUpdatedState(onLift)
    val passSlot by rememberUpdatedState(onSlot)
    val drop by rememberUpdatedState(onDrop)
    // Lifted, the row grows a touch. Read where it's used (the layer, the gesture), so the lift never
    // recomposes the row.
    fun scale() = 1f + 0.015f * lifted
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier
            .zIndex(if (dragged) 1f else 0f)
            .offset { IntOffset(0, y.dp.roundToPx()) }
            .fillMaxWidth()
            .height(ROW_DP.dp)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
                shadowElevation = 6.dp.toPx() * lifted
                shape = RowShape
            }
            .drawBehind {
                if (lifted > 0f) {
                    drawOutline(RowShape.createOutline(size, layoutDirection, this), LiftedFill.copy(alpha = lifted))
                }
                if (line > 0f) {
                    val inset = 84.dp.toPx()
                    drawLine(
                        Palette.Line.copy(alpha = Palette.Line.alpha * line),
                        start = Offset(inset, 0f),
                        end = Offset(size.width - Space.S2.toPx(), 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                stateDescription = "${ordinal(index + 1)} in the dock"
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move up") { onStep(-1); true })
                    if (index < count - 1) add(CustomAccessibilityAction("Move down") { onStep(1); true })
                }
            }
            .padding(start = Space.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCover(
            app = app,
            shape = ThumbShape,
            nameStyle = Type.Caption,
            nameLines = 1,
            modifier = Modifier.size(width = 64.dp, height = 36.dp),
        )
        Spacer(Modifier.width(Space.S3))
        BasicText(
            app.name,
            style = Type.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.S3))
        Box(
            Modifier
                // TalkBack moves rows with the row's own actions; the handle is for fingers and keys.
                .clearAndSetSemantics {}
                .size(44.dp)
                .focusRing(focused, HandleShape, offset = (-2).dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            onStep(-1)
                            true
                        }
                        Key.DirectionDown -> {
                            onStep(1)
                            true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
                .pointerInput(app.packageName) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // One row at a time: a second finger, or a press while a row settles, does nothing.
                        if (drag.active) return@awaitEachGesture
                        down.consume()
                        drag.lift(app.packageName, currentIndex)
                        lift()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                // A change between two events, so the row moving under the finger doesn't
                                // count. The handle is inside the row's scaled layer and hears the move that
                                // much smaller: scaled back up, the row stays exactly under the finger.
                                val dy = change.positionChange().y.toDp().value * scale()
                                change.consume()
                                if (drag.moveBy(dy, currentCount)) passSlot()
                            }
                        } finally {
                            // Let go, or the gesture was cut short: either way the row settles where it is.
                            drop()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(R.drawable.ph_dots_six_vertical, 20.dp, if (dragged) Palette.Text else Palette.Text2)
        }
    }
}
