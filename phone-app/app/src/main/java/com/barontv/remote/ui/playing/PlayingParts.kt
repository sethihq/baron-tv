package com.barontv.remote.ui.playing

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.barontv.remote.R
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.tv.TV_NAME
import com.barontv.remote.ui.Glyph
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.SquircleShape
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.Type
import com.barontv.remote.ui.focusRing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val CoverShape = SquircleShape(20.dp)

/** cubic-bezier(0.32, 0.72, 0, 1): the prototype's sheet curve, for the cover settling back. */
private val EaseSheet = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** Paused, the cover settles back to this size, as the music app on the phone does. */
private const val PAUSED_SCALE = 0.86f

/** A shared 6% sheen down the top half, as every cover has, so a calm tile sits with the real ones. */
private val Sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.06f), 0.55f to Color.White.copy(alpha = 0f))

/** oklch(100% 0 none / 0.1): the hairline round every piece of artwork, so a dark cover keeps its edge. */
private val Hairline = Color.White.copy(alpha = 0.1f)

/** The artist line: the body face, a size up. */
private val ArtistStyle = Type.Body.copy(color = Palette.Text2, fontSize = 16.sp)

/** The times under the progress line, in figures of one width so they don't jitter as they count. */
private val TimeStyle = Type.Caption.copy(color = Palette.Text2, fontFeatureSettings = "tnum")

/** "Spotify on Baron TV": the caption face at 13, with no extra tracking. */
private val SourceStyle = Type.Caption.copy(color = Palette.Text2, fontSize = 13.sp, letterSpacing = 0.sp)

/** oklch(88% 0 none): the play button pressed. */
private val PlayPressed = Color(0xFFD7D7D7)

/**
 * The app's cover in place of album art (the TV can't send any): Spotify's own cover, or a calm tile in
 * the app's colour with its name. 16:9, the covers' own shape, so a TV banner is never cropped to a square.
 * Paused, it settles back to 0.86 over 520 ms with a smaller shadow; with less motion it only loses the
 * shadow's depth.
 */
@Composable
internal fun Cover(
    look: AppLook,
    appName: String,
    paused: Boolean,
    lessMotion: Boolean,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    // Read in the layer and the drawing only, so settling never recomposes the tab.
    val settle = animateFloatAsState(
        targetValue = if (paused) 1f else 0f,
        animationSpec = if (lessMotion) snap() else tween(520, easing = EaseSheet),
        label = "cover settle",
    )
    Box(
        modifier
            .size(width, width * 9f / 16f)
            // A picture of the app, whose name TalkBack already reads under it.
            .clearAndSetSemantics {}
            .graphicsLayer {
                val scale = if (lessMotion) 1f else lerp(1f, PAUSED_SCALE, settle.value)
                scaleX = scale
                scaleY = scale
            }
            .drawBehind { drawCoverShadow(settle.value) }
            .clip(CoverShape)
            .background(if (look.cover != null) Palette.Fill else look.tile)
            .drawWithCache {
                val outline = CoverShape.createOutline(size, layoutDirection, this)
                // Twice as wide as the hairline, centred on the edge: the clip keeps the inner half.
                val stroke = Stroke(2.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawOutline(outline, Hairline, style = stroke)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (look.cover != null) {
            Image(
                painter = painterResource(look.cover),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Sheen)
                    .padding(horizontal = Space.S6),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(appName, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The prototype's `0 14px 32px` black at 32% under the cover, `0 6px 16px` at 24% when paused. Only from
 * Android 9, the first that blurs on the GPU; before that it would draw a hard dark block, and on black
 * the shadow only ever shows faintly over the glow anyway.
 */
private fun DrawScope.drawCoverShadow(settle: Float) {
    if (Build.VERSION.SDK_INT < 28) return
    val drop = lerp(14.dp.toPx(), 6.dp.toPx(), settle)
    // CSS blurs with a Gaussian of half the blur length; Skia's blur radius maps to it as below.
    val sigma = lerp(32.dp.toPx(), 16.dp.toPx(), settle) / 2f
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb((lerp(0.32f, 0.24f, settle) * 255).roundToInt(), 0, 0, 0)
        maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }
    val radius = 20.dp.toPx()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, drop, size.width, size.height + drop, radius, radius, paint) }
}

/**
 * The song and the artist, or, when the TV couldn't read them off the music app's screen, the app's name
 * large and where it's playing. One stop for TalkBack; a new song cross-fades in.
 */
@Composable
internal fun NowText(now: NowPlaying, appName: String, width: Dp, modifier: Modifier = Modifier) {
    val lines = if (now.title.isNotBlank()) {
        Lines(now.title, now.artist.ifBlank { appName }, song = true)
    } else {
        Lines(appName, "${if (now.playing) "Playing" else "Paused"} on $TV_NAME", song = false)
    }
    AnimatedContent(
        targetState = lines,
        modifier = modifier
            .width(width)
            .semantics(mergeDescendants = true) {},
        transitionSpec = { fadeIn(tween(200, easing = Motion.EaseOut)) togetherWith fadeOut(tween(120)) },
        label = "now text",
    ) { shown ->
        Column(Modifier.fillMaxWidth()) {
            BasicText(
                shown.first,
                style = if (shown.song) Type.Title else Type.Large,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            BasicText(shown.second, style = ArtistStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Lines(val first: String, val second: String, val song: Boolean)

/** A song's line and its two times take 28 dp: the 4 dp line, 8 dp, then 16 dp of caption. */
internal val ProgressHeight = 28.dp

/**
 * How far into the song: a 4 dp line, then the time gone on the left and the time left on the right. It
 * runs on by itself between polls, so it glides instead of stepping once a second, and only jumps when a
 * poll finds it more than a second out (a seek, a new song, a stall).
 */
@Composable
internal fun ProgressLine(now: NowPlaying, width: Dp, modifier: Modifier = Modifier) {
    val duration = now.durationMs.coerceAtLeast(1L).toFloat()
    val fraction = remember { Animatable((now.positionMs / duration).coerceIn(0f, 1f)) }
    LaunchedEffect(now.positionMs, now.durationMs, now.playing, now.title) {
        val target = (now.positionMs / duration).coerceIn(0f, 1f)
        if (!now.playing || abs(fraction.value - target) * duration > DRIFT_MS) fraction.snapTo(target)
        if (now.playing) {
            val left = ((1f - fraction.value) * duration).roundToInt()
            if (left > 0) fraction.animateTo(1f, tween(left, easing = LinearEasing))
        }
    }
    // Whole seconds, so the times recompose once a second while the line itself moves every frame.
    val totalSeconds = (now.durationMs / 1000f).roundToInt()
    val gone by remember(duration) { derivedStateOf { (fraction.value * duration / 1000f).toInt() } }

    Column(
        modifier
            .width(width)
            .clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(Palette.Veil3, cornerRadius = radius)
                    // The fill's right end is square, as the prototype's scaled bar inside a rounded track.
                    clipRect(right = size.width * fraction.value) { drawRoundRect(Palette.Text, cornerRadius = radius) }
                },
        )
        Spacer(Modifier.height(Space.S2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(clock(gone), style = TimeStyle)
            BasicText("-" + clock((totalSeconds - gone).coerceAtLeast(0)), style = TimeStyle)
        }
    }
}

/** Polls more than this far from where the line has run to move it at once; nearer ones let it glide. */
private const val DRIFT_MS = 1_200f

/** m:ss, or h:mm:ss past the hour (a film). */
internal fun clock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val mm = (s / 60) % 60
    val ss = (s % 60).toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:${mm.toString().padStart(2, '0')}:$ss" else "${s / 60}:$ss"
}

/**
 * Previous, play or pause, and next, as the prototype's row: 56 dp buttons either side of a 72 dp white
 * one, 24 dp in from the column's edges. Each press plays a tap and dips the glyph once.
 */
@Composable
internal fun Transport(
    playing: Boolean,
    width: Dp,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .width(width)
            .padding(horizontal = Space.S6),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControl(R.drawable.ph_skip_back_fill, "Previous", onPrevious)
        RoundControl(
            icon = if (playing) R.drawable.ph_pause_fill else R.drawable.ph_play_fill,
            spoken = if (playing) "Pause" else "Play",
            onClick = onPlayPause,
            size = 72.dp,
            fill = Palette.Text,
            pressedFill = PlayPressed,
            tint = Color.Black,
            pressedScale = 0.95f,
        )
        RoundControl(R.drawable.ph_skip_forward_fill, "Next", onNext)
    }
}

/**
 * One round button. Pressed, it dips (0.94, or 0.95 for play) and its fill brightens over 160 ms, as the
 * prototype's `.np-btn:active`; no ripple. Every press also dips the glyph to 0.95 and back over 180 ms,
 * so even the quickest tap shows. Keyboard focus draws the ring 3 dp outside.
 */
@Composable
private fun RoundControl(
    @DrawableRes icon: Int,
    spoken: String,
    onClick: () -> Unit,
    size: Dp = 56.dp,
    fill: Color = Color.Transparent,
    pressedFill: Color = Palette.Veil,
    tint: Color = Palette.Text,
    pressedScale: Float = 0.94f,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press = animateFloatAsState(if (pressed) pressedScale else 1f, tween(160, easing = Motion.EaseOut), label = "control press")
    val colour = animateColorAsState(if (pressed) pressedFill else fill, tween(160, easing = Motion.EaseOut), label = "control fill")
    val pulse = remember { Animatable(1f) }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .focusRing(focused, CircleShape, offset = 3.dp)
            .drawBehind { drawCircle(colour.value) }
            .clickable(interaction, indication = null, role = Role.Button) {
                onClick()
                scope.launch {
                    pulse.snapTo(0.95f)
                    pulse.animateTo(1f, tween(180, easing = Motion.EaseOut))
                }
            }
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            icon,
            30.dp,
            tint,
            Modifier.graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            },
        )
    }
}

/** "Spotify on Baron TV", with the app's mark, under the volume. */
@Composable
internal fun SourceLine(look: AppLook, appName: String, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(look.mark, 16.dp, look.markTint)
        BasicText("$appName on $TV_NAME", style = SourceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The empty state's mark: the Playing tab's own music notes on a key's veil. */
@Composable
internal fun NotesMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(60.dp)
            .background(Palette.Veil, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(R.drawable.ph_music_notes, 26.dp, Palette.Text2)
    }
}

/** The row of controls is as tall as the play button. */
internal val ControlsHeight = 72.dp

/** The source line is one 16 dp caption line. */
internal val SourceHeight = 16.dp
