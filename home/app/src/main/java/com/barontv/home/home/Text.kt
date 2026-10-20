package com.barontv.home.home

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.barontv.home.core.dp

/**
 * Text the way the prototype's CSS sets it: a size in dp (sp = dp on this screen), tracking in em, tabular
 * figures where the numbers shouldn't jiggle, and lines placed by line box (a line-height tall box with the
 * text centred in it), so baselines land where Chrome puts them. Linear metrics: the font's own advances,
 * as Chrome sets them, rather than advances rounded by hinting, which run about 4% narrower at these sizes.
 */
internal fun Context.textPaint(
    typeface: Typeface,
    sizeDp: Float,
    color: Int,
    trackingEm: Float = 0f,
    tabular: Boolean = false,
): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
    this.typeface = typeface
    textSize = dp(sizeDp)
    this.color = color
    letterSpacing = trackingEm
    if (tabular) fontFeatureSettings = "tnum"
}

private val metrics = Paint.FontMetrics()

/** The baseline of a line of [paint]'s text in a line box [lineHeight] tall whose top is at [top] (px). */
internal fun baselineIn(paint: Paint, top: Float, lineHeight: Float): Float {
    paint.getFontMetrics(metrics)
    return top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
}

/** A line box of "normal" height for [paint]: the font's ascent plus descent, as CSS's line-height normal. */
internal fun normalLineHeight(paint: Paint): Float {
    paint.getFontMetrics(metrics)
    return metrics.descent - metrics.ascent
}
