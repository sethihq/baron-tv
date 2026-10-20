package com.barontv.remote.ui.playing

import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.barontv.remote.ui.Motion
import kotlin.math.max
import kotlin.math.min

/** How far the glow reaches below its heart: the prototype's 58% of a 700 dp box. */
private val Reach = 406.dp

/** Its width, as a share of the screen's: a little wider, so it still tints the edges beside the cover. */
private const val WIDTH_SHARE = 0.95f

/** Its heart sits this share of the cover's height above the cover's middle, as on the prototype's square cover. */
private const val HEART_ABOVE_MIDDLE = 0.16f

/** Where the inner colour has thinned to the outer one, from the heart to the edge. */
private const val OUTER_AT = 0.55f

/**
 * The cover's colour glowing behind the Playing tab, as the prototype's `.np-glow`: an ellipse slightly
 * wider than the screen, brightest just above the middle of the cover, thinned to its outer colour by
 * 55% of the way out and gone by its edge. A new app's colour morphs in over 700 ms.
 *
 * The prototype's glow reaches up behind the header and the status bar. The tab only owns its own area,
 * so here it fades out towards the top of it, never stopping at a line under the header, and it ends
 * before the bottom of the area. [cover] is where the cover sits in this area, or null before it's placed.
 */
@Composable
internal fun Glow(vivid: Color, cover: () -> Rect?, modifier: Modifier = Modifier) {
    val target = remember(vivid) { glowFor(vivid) }
    // Read in the drawing only, so the morph redraws the glow without recomposing the tab.
    val inner = animateColorAsState(target.inner, tween(700, easing = Motion.EaseOut), label = "glow inner")
    val outer = animateColorAsState(target.outer, tween(700, easing = Motion.EaseOut), label = "glow outer")
    Box(
        modifier
            // Its own layer, so the fade at the top takes away the glow alone, never what's drawn above it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val anchor = cover()
                if (anchor == null || size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind {}
                val cx = size.width / 2f
                val cy = anchor.center.y - anchor.height * HEART_ABOVE_MIDDLE
                val rx = size.width * WIDTH_SHARE
                // Fitted above the bottom of the area, so it never ends in a line above the tab bar.
                val ry = min(Reach.toPx(), max(size.height - cy, Reach.toPx() / 2f))
                val (colours, stops) = glowStops(inner.value, outer.value)
                val paint = android.graphics.Paint().apply {
                    // Dithered: a faint gradient on black would otherwise show bands.
                    isDither = true
                    shader = RadialGradient(cx, cy, rx, colours, stops, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) })
                    }
                }
                // Gone at the top edge, whole by the cover's middle, easing in between.
                val fadeTo = max(anchor.center.y, 1f)
                val fade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to Color.Black.copy(alpha = 0.16f),
                    0.5f to Color.Black.copy(alpha = 0.5f),
                    0.75f to Color.Black.copy(alpha = 0.84f),
                    1f to Color.Black,
                    startY = 0f,
                    endY = fadeTo,
                )
                onDrawBehind {
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                    drawRect(fade, blendMode = BlendMode.DstIn)
                }
            },
    )
}

/**
 * The gradient's colours and where they sit: the inner colour at the heart, mixed towards the outer one
 * in OKLab as Chrome does (a few steps, since the phone blends stops in sRGB), then the outer colour
 * fading to nothing. It fades in its own colour rather than to transparent black, which would grey it.
 */
private fun glowStops(inner: Color, outer: Color, steps: Int = 6): Pair<IntArray, FloatArray> {
    val colours = IntArray(steps + 2)
    val stops = FloatArray(steps + 2)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        colours[i] = mixOklab(inner, outer, t).toArgb()
        stops[i] = OUTER_AT * t
    }
    colours[steps + 1] = outer.copy(alpha = 0f).toArgb()
    stops[steps + 1] = 1f
    return colours to stops
}
