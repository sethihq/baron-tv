package com.barontv.remote.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rounded rectangle with squircle corners, as the prototype draws the touch pad
 * (`border-radius: 32px; corner-shape: squircle`). Each corner is a quarter of the superellipse
 * x⁴ + y⁴ = 1 inside a [radius] square: it leaves the straight edge more gently than a circle and turns
 * tighter in the middle. Measured on Chrome's render of the prototype, the corner crosses the diagonal
 * at 0.16 of the radius, as this curve does; a circle would cross at 0.29.
 */
class SquircleShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(with(density) { radius.toPx() }, min(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        val path = Path()
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        corner(path, r, cx = w - r, cy = r, sx = 1f, sy = -1f, descending = true)
        path.lineTo(w, h - r)
        corner(path, r, cx = w - r, cy = h - r, sx = 1f, sy = 1f, descending = false)
        path.lineTo(r, h)
        corner(path, r, cx = r, cy = h - r, sx = -1f, sy = 1f, descending = true)
        path.lineTo(0f, r)
        corner(path, r, cx = r, cy = r, sx = -1f, sy = -1f, descending = false)
        path.close()
        return Outline.Generic(path)
    }

    // One corner round its centre (cx, cy). With the exponent 4 the curve is (r·√cos t, r·√sin t) for t
    // from 0 to 90 degrees, and sx and sy pick the quadrant. Going clockwise, the top-right and
    // bottom-left corners run from t = 90 down to 0, the other two from 0 up. 24 steps keep the line
    // within two hundredths of a dp of the true curve at the pad's 32 dp radius.
    private fun corner(path: Path, r: Float, cx: Float, cy: Float, sx: Float, sy: Float, descending: Boolean) {
        for (i in 1..STEPS) {
            val k = if (descending) STEPS - i else i
            val t = (PI / 2.0) * k / STEPS
            val x = cx + sx * r * sqrt(cos(t)).toFloat()
            val y = cy + sy * r * sqrt(sin(t)).toFloat()
            path.lineTo(x, y)
        }
    }

    override fun equals(other: Any?) = other is SquircleShape && other.radius == radius

    override fun hashCode() = radius.hashCode()

    override fun toString() = "SquircleShape($radius)"

    private companion object {
        const val STEPS = 24
    }
}
