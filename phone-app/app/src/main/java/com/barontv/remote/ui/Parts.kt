package com.barontv.remote.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A Phosphor icon (a white vector drawable) in [tint], as the prototype's `.glyph` masks. */
@Composable
internal fun Glyph(@DrawableRes icon: Int, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * The prototype's keyboard focus ring (`outline: 2px solid var(--ring)`), drawn round [shape] only while
 * [focused]. It shows for a hardware keyboard or D-pad; touch never moves focus, so it never shows for a
 * finger. A positive [offset] puts it outside the element with that gap, as `outline-offset: 2px`; a
 * negative one draws it inside, for elements whose edge is the screen's own (the pad, the volume pill).
 */
internal fun Modifier.focusRing(focused: Boolean, shape: Shape, offset: Dp = 2.dp, width: Dp = 2.dp): Modifier =
    if (!focused) {
        this
    } else {
        drawWithCache {
            val grow = (offset + width / 2).toPx()
            val outline = shape.createOutline(
                Size(size.width + grow * 2, size.height + grow * 2),
                layoutDirection,
                this,
            )
            val stroke = Stroke(width.toPx())
            onDrawWithContent {
                drawContent()
                translate(-grow, -grow) { drawOutline(outline, Palette.Ring, style = stroke) }
            }
        }
    }

/**
 * A small rounded button for the one thing to do next ("Try again"). It sits on [Palette.Panel], as the
 * prototype's floating pills do, takes a veil when pressed and dips to 0.97, like the prototype's tiles.
 * The visible pill is 40 dp tall; the touch area grows to 48.
 */
@Composable
internal fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160, easing = Motion.EaseOut), label = "pill scale")
    val fill by animateColorAsState(
        if (pressed) Palette.Veil.compositeOver(Palette.Panel) else Palette.Panel,
        tween(160, easing = Motion.EaseOut),
        label = "pill fill",
    )
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(focused, CircleShape)
            .clip(CircleShape)
            .background(fill)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Space.S5),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Body)
    }
}
