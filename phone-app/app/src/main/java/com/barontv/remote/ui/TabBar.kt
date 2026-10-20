package com.barontv.remote.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barontv.remote.R
import kotlinx.coroutines.launch

/** The app's four sections, in the tab bar's order, each with its Phosphor glyph (regular, and fill when chosen). */
internal enum class Tab(val label: String, @DrawableRes val icon: Int, @DrawableRes val iconChosen: Int) {
    Remote("Remote", R.drawable.ph_arrows_out_cardinal, R.drawable.ph_arrows_out_cardinal_fill),
    Apps("Apps", R.drawable.ph_squares_four, R.drawable.ph_squares_four_fill),
    Playing("Playing", R.drawable.ph_music_notes, R.drawable.ph_music_notes_fill),
    Tv("TV", R.drawable.ph_television, R.drawable.ph_television_fill),
    ;

    companion object {
        /** The tab saved under [name], or Remote for anything unknown (a first launch, a renamed tab). */
        fun named(name: String?): Tab = entries.firstOrNull { it.name == name } ?: Remote
    }
}

/** oklch(100% 0 none / 0.08): the faint pill behind the chosen tab's glyph. */
private val ChosenPill = Color.White.copy(alpha = 0.08f)

private val TabLabel = Type.Caption.copy(fontWeight = FontWeight.SemiBold)

/**
 * The bottom tab bar, as the prototype's `.tabs`: four equal tabs, each a glyph in a 60 by 32 pill over
 * its label. The chosen tab's pill takes a faint veil (fading in over 200 ms), its glyph turns filled and
 * sky blue and its label brightens. A press dips the pill to 0.94; choosing a new tab plays the "select"
 * haptic with a small pulse on its pill. [onSelect] also hears a tap on the tab that is already chosen.
 */
@Composable
internal fun TabBar(
    chosen: Tab,
    onSelect: (Tab) -> Unit,
    haptics: Haptics,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .widthIn(max = MAX_CONTENT_WIDTH)
            .fillMaxWidth()
            .selectableGroup()
            .padding(horizontal = Space.S2)
            .padding(top = Space.S2, bottom = Space.S1),
    ) {
        for (tab in Tab.entries) {
            TabItem(
                tab = tab,
                chosen = tab == chosen,
                onClick = {
                    if (tab != chosen) haptics.select()
                    onSelect(tab)
                },
                reduceMotion = reduceMotion,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    chosen: Boolean,
    onClick: () -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        tween(160, easing = Motion.EaseOut),
        label = "tab press",
    )
    val pill by animateColorAsState(
        if (chosen) ChosenPill else Color.Transparent,
        tween(200, easing = Motion.EaseOut),
        label = "tab pill",
    )
    val pulse = remember { Pulse() }

    Column(
        modifier
            .selectable(
                selected = chosen,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = {
                    if (!chosen) scope.launch { pulse.play(reduceMotion) }
                    onClick()
                },
            )
            .heightIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    // The lower of the two, so a held tab coming back up and the pulse meet without a jump.
                    val s = minOf(press, pulse.scale)
                    scaleX = s
                    scaleY = s
                    alpha = pulse.alpha
                }
                .focusRing(focused, CircleShape)
                .size(width = 60.dp, height = 32.dp)
                .background(pill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                icon = if (chosen) tab.iconChosen else tab.icon,
                size = 22.dp,
                tint = if (chosen) Palette.Accent else Palette.Text2,
            )
        }
        // The prototype's grid stretches both rows by 2 to reach 56, so the label sits 6 under the pill.
        Spacer(Modifier.height(6.dp))
        BasicText(tab.label, style = TabLabel.copy(color = if (chosen) Palette.Text else Palette.Text2), maxLines = 1)
    }
}

/**
 * The visible half of a haptic, as the prototype's `pulse()`: a quick dip to 0.95 that springs back over
 * 180 ms. With reduced motion nothing moves; it dims to 70% and comes back instead.
 */
internal class Pulse {
    private val dip = Animatable(1f)
    private val dim = Animatable(1f)

    val scale: Float get() = dip.value
    val alpha: Float get() = dim.value

    suspend fun play(reduceMotion: Boolean) {
        if (reduceMotion) {
            dim.snapTo(0.7f)
            dim.animateTo(1f, tween(180))
        } else {
            dip.snapTo(0.95f)
            dip.animateTo(1f, tween(180, easing = Motion.EaseOut))
        }
    }
}

// ---- The page under the header: how the Apps and TV tabs lay out their content ----------------------

/** As wide as a phone held upright: on anything wider (landscape, a tablet) tabs keep this and centre. */
internal val MAX_CONTENT_WIDTH: Dp = 480.dp

/**
 * A tab that scrolls, as the prototype's `.views`: 20 dp side margins, 8 above and 32 below the content,
 * and the content fading out over the top 12 dp and the bottom 24 dp, so it slips away under the header
 * and the tab bar instead of being cut. [reselected] scrolls back to the top whenever it changes: the
 * chosen tab tapped again.
 */
@Composable
internal fun TabPage(
    scroll: ScrollState,
    reselected: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(reselected) {
        if (reselected > 0) scroll.animateScrollTo(0)
    }
    Box(
        modifier
            .fadingEdges(top = Space.S3, bottom = Space.S6)
            .verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = Space.Edge)
                .padding(top = Space.S2, bottom = Space.S8),
            content = content,
        )
    }
}

/** The prototype's mask: transparent at both ends, fully drawn from [top] down to [bottom] from the end. */
private fun Modifier.fadingEdges(top: Dp, bottom: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            if (h <= 0f) return@drawWithContent
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    (top.toPx() / h).coerceAtMost(0.5f) to Color.Black,
                    (1f - bottom.toPx() / h).coerceAtLeast(0.5f) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** A tab's large title ("Apps", "TV"): 28 bold, 8 dp under the top of the page. */
@Composable
internal fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = Type.Large,
        modifier = modifier
            .padding(top = Space.S2)
            .semantics { heading() },
    )
}

/** The quieter line right under a large title. */
@Composable
internal fun Lede(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = Type.Body.copy(color = Palette.Text2), modifier = modifier.padding(top = Space.S1))
}
