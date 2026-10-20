package com.barontv.remote.ui.apps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barontv.remote.tv.TvApp
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.ui.LargeTitle
import com.barontv.remote.ui.Lede
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Pulse
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.SquircleShape
import com.barontv.remote.ui.TabPage
import com.barontv.remote.ui.Type
import com.barontv.remote.ui.focusRing
import com.barontv.remote.ui.rememberHaptics
import com.barontv.remote.ui.rememberReduceMotion
import kotlinx.coroutines.launch

/** How far the grid fades while there's no TV: still readable, plainly asleep, as the Remote tab's pad. */
private const val DIMMED = 0.45f

/** The cards' corners, squircles as the prototype's `.app-card`. */
private val CardShape = SquircleShape(16.dp)

/** oklch(100% 0 none / 0.1): the hairline round every piece of artwork, so a black cover keeps its edge. */
private val Hairline = Color.White.copy(alpha = 0.1f)

/**
 * The Apps tab: "Apps", one line on what a tap does, and the TV's apps as covers in two columns, in the
 * TV's dock order (pinned first). A tap opens the app on the TV.
 *
 * The list comes from the TV's new home ([TvApps]). Until it has answered (no bridge, or no TV), the
 * prototype's seven stand in, Netflix marked "Not on this TV"; a tap still tries [TvBridge.launch], which
 * falls back to CloudWalker's own way of opening apps. While the TV isn't connected the grid dims, as the
 * Remote tab's pad does, and a tap that can't reach the TV buzzes "no" and says so.
 */
@Composable
internal fun AppsTab(
    apps: TvApps,
    bridge: TvBridge,
    connected: Boolean,
    say: (String) -> Unit,
    reselected: Int,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val listed = apps.list
    val shown = listed ?: PrototypeApps
    val dim by animateFloatAsState(
        if (connected) 1f else DIMMED,
        tween(300, easing = Motion.EaseOut),
        label = "apps dim",
    )

    fun open(app: TvApp, blocked: Boolean) {
        if (blocked) {
            haptics.reject()
            say("${app.name} can't run on this TV")
            return
        }
        // Felt at once when the TV is there; without it, the answer decides.
        if (connected) haptics.confirm()
        scope.launch {
            if (!bridge.launch(app.packageName)) {
                haptics.reject()
                say("Couldn't open ${app.name} on the TV")
            }
        }
    }

    TabPage(rememberScrollState(), reselected, modifier) {
        LargeTitle("Apps")
        Lede("Tap an app to open it on the TV.")
        Spacer(Modifier.height(Space.S6))
        Column(
            Modifier.graphicsLayer { alpha = dim },
            verticalArrangement = Arrangement.spacedBy(Space.S6),
        ) {
            for (pair in shown.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S4)) {
                    for (app in pair) {
                        // Only the stand-in list knows Netflix can't run here; the TV lists what it has.
                        val blocked = listed == null && app.packageName == NETFLIX
                        key(app.packageName) {
                            AppTile(
                                app = app,
                                note = if (blocked) "Not on this TV" else null,
                                onOpen = { open(app, blocked) },
                                reduceMotion = reduceMotion,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(Space.S10))
    }
}

/**
 * One app: its cover, 16 by 9, and its name under it, with an optional quiet [note]. Pressed, the cover
 * dips to 0.97; a tap pulses it as the haptic plays, or, for an app that can't run ([note] set), gives it
 * a small shake, as a wrong passcode does.
 */
@Composable
private fun AppTile(
    app: TvApp,
    note: String?,
    onOpen: () -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        tween(160, easing = Motion.EaseOut),
        label = "app press",
    )
    val pulse = remember { Pulse() }
    val shake = remember { Animatable(0f) }

    Column(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (note == null) "open it on the TV" else null,
            ) {
                scope.launch {
                    if (note != null && !reduceMotion) shake.animateTo(0f, Shake) else pulse.play(reduceMotion)
                }
                onOpen()
            },
    ) {
        AppCover(
            app = app,
            shape = CardShape,
            nameStyle = Type.Headline,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    val s = minOf(press, pulse.scale)
                    scaleX = s
                    scaleY = s
                    alpha = pulse.alpha
                    translationX = shake.value * density
                }
                .focusRing(focused, CardShape, offset = 3.dp),
        )
        Spacer(Modifier.height(Space.S2))
        BasicText(
            app.name,
            style = Type.Body,
            modifier = Modifier.padding(horizontal = 2.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) {
            // The prototype pulls the note 6 up from the tile's 8 gap.
            Spacer(Modifier.height(2.dp))
            BasicText(
                note,
                style = Type.Caption.copy(color = Palette.Text3),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** The prototype's reject shake, in dp: left 3, right 3, left 2, home, over 260 ms. */
private val Shake = keyframes {
    durationMillis = 260
    0f at 0
    -3f at 65
    3f at 130
    -2f at 195
    0f at 260
}

/**
 * An app's artwork: its cover when the app ships one ([coverOf]), else a calm tile with its name in
 * [nameStyle] (the TV lists apps this app has no art for). Either way with the prototype's hairline, a
 * white 10% line just inside the edge, so a black cover still reads as a card.
 */
@Composable
internal fun AppCover(
    app: TvApp,
    shape: Shape,
    nameStyle: TextStyle,
    modifier: Modifier = Modifier,
    nameLines: Int = 2,
) {
    val cover = coverOf(app.packageName)
    Box(
        modifier
            .clip(shape)
            .background(Palette.Fill)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                // Centred on the edge, so half of it falls outside the clip: 1 dp shows, as `inset 0 0 0 1px`.
                val stroke = Stroke(width = 2.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawOutline(outline, Hairline, style = stroke)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                painter = painterResource(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            BasicText(
                app.name,
                style = nameStyle.copy(color = Palette.Text2, textAlign = TextAlign.Center),
                // The name is said once, by the label beside or under the art.
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .padding(horizontal = Space.S2),
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
