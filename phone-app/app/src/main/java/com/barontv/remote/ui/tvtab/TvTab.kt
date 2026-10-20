package com.barontv.remote.ui.tvtab

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barontv.remote.R
import com.barontv.remote.tv.Shortcut
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvInput
import com.barontv.remote.ui.Glyph
import com.barontv.remote.ui.LargeTitle
import com.barontv.remote.ui.Lede
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Pulse
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.SquircleShape
import com.barontv.remote.ui.TabPage
import com.barontv.remote.ui.Type
import com.barontv.remote.ui.apps.TvApps
import com.barontv.remote.ui.apps.ordinal
import com.barontv.remote.ui.focusRing
import com.barontv.remote.ui.rememberHaptics
import com.barontv.remote.ui.rememberReduceMotion
import kotlinx.coroutines.launch

private val TileShape = SquircleShape(20.dp)
private val CardShape = SquircleShape(22.dp)

/** 15 semibold: tile names and the card's heading. */
private val Strong = Type.Body.copy(fontWeight = FontWeight.SemiBold)

/** 13 on 16, untracked, quieter: the line under the card's heading. */
private val CardSub = Type.Caption.copy(color = Palette.Text2, fontSize = 13.sp, letterSpacing = 0.sp)

/** The three shortcuts into the TV's own screens, with what the notice calls each one. */
private enum class ShortcutTile(
    val label: String,
    @DrawableRes val icon: Int,
    val shortcut: Shortcut,
    val what: String,
) {
    Picture("Picture", R.drawable.ph_image, Shortcut.PICTURE, "the picture menu"),
    Sound("Sound", R.drawable.ph_speaker_high, Shortcut.SOUND, "the sound menu"),
    Settings("Settings", R.drawable.ph_gear_six, Shortcut.SETTINGS, "Settings"),
}

/**
 * The TV tab: "TV", then Inputs (HDMI 1, HDMI 2 and AV, each saying whether something is plugged in),
 * Shortcuts (Picture, Sound, Settings) and Customise home, where the dock's apps are dragged into the
 * order the TV shows them in. Everything here goes through the TV's new home, so while it isn't
 * answering the tab says so in one quiet line instead.
 *
 * [scroll] and [drag] are only passed in by snapshots, to show the dock list mid-drag.
 */
@Composable
internal fun TvTab(
    apps: TvApps,
    inputs: TvInputs,
    bridge: TvBridge,
    say: (String) -> Unit,
    reselected: Int,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    drag: DockDrag = remember { DockDrag() },
) {
    val answering by bridge.available.collectAsState()
    val haptics = rememberHaptics()
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()

    TabPage(scroll, reselected, modifier) {
        LargeTitle("TV")
        if (!answering) {
            Lede("Inputs, shortcuts and the dock order need the TV's new home, which isn't answering.")
        } else {
            Group("Inputs") {
                TileRow {
                    for (input in inputs.list ?: TheTvsInputs) {
                        Tile(
                            icon = iconOf(input),
                            name = input.name,
                            state = if (input.live) "Connected" else "Not connected",
                            quiet = !input.live,
                            onClickLabel = "switch the TV to it",
                            reduceMotion = reduceMotion,
                            // The TV reports most inputs as not connected even when they are, so every
                            // input switches: the tile only reads quieter.
                            onClick = {
                                haptics.tap()
                                scope.launch {
                                    if (!bridge.switchInput(input.name)) {
                                        haptics.reject()
                                        say("Couldn't switch the TV to ${input.name}")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Group("Shortcuts") {
                TileRow {
                    for (tile in ShortcutTile.entries) {
                        Tile(
                            icon = tile.icon,
                            name = tile.label,
                            state = null,
                            quiet = false,
                            onClickLabel = "open it on the TV",
                            reduceMotion = reduceMotion,
                            onClick = {
                                haptics.tap()
                                scope.launch {
                                    if (!bridge.shortcut(tile.shortcut)) {
                                        haptics.reject()
                                        say("Couldn't open ${tile.what} on the TV")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // Shown once the TV has listed its apps; it usually has by now (the shell asks on connecting).
            val pinned = apps.list.orEmpty().filter { it.pinned }
            if (pinned.isNotEmpty()) {
                Group("Customise home") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.Veil, CardShape)
                            .padding(vertical = Space.S4),
                    ) {
                        BasicText("Dock order", style = Strong, modifier = Modifier.padding(horizontal = Space.S4))
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            "Drag to change the order on the TV.",
                            style = CardSub,
                            modifier = Modifier.padding(horizontal = Space.S4),
                        )
                        Spacer(Modifier.height(Space.S2))
                        DockList(
                            apps = pinned,
                            onMove = { packageName, to ->
                                // On the phone at once, in the same frame as the drop; then to the TV.
                                val move = apps.moveInDock(packageName, to)
                                if (move != null) {
                                    scope.launch {
                                        if (apps.send(move)) {
                                            say("${move.moved.name} is now ${ordinal(to + 1)} in the TV's dock")
                                        } else {
                                            haptics.reject()
                                            say("Couldn't save the dock order on the TV")
                                        }
                                    }
                                }
                            },
                            haptics = haptics,
                            reduceMotion = reduceMotion,
                            // The rows reach 8 into the card's padding, as the prototype's list does.
                            modifier = Modifier.padding(horizontal = Space.S2),
                            drag = drag,
                        )
                    }
                    BasicText(
                        "Changes show on the TV straight away.",
                        style = Type.Caption.copy(color = Palette.Text3),
                        modifier = Modifier
                            .padding(top = Space.S3)
                            .padding(horizontal = Space.S1),
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.S10))
    }
}

/** An input's glyph: a plug for HDMI (plugged in, or not), a small TV for AV and anything else. */
@DrawableRes
private fun iconOf(input: TvInput): Int = when {
    !input.name.startsWith("HDMI", ignoreCase = true) -> R.drawable.ph_television_simple
    input.live -> R.drawable.ph_plugs_connected
    else -> R.drawable.ph_plug
}

/** A section of the tab: its heading 28 under what's above, and its content 12 under the heading. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = Space.S7)) {
        BasicText(title, style = Type.Headline, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.S3))
        content()
    }
}

/** Three tiles a row, 12 apart, all as tall as the tallest. */
@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Space.S3),
        content = content,
    )
}

/**
 * A tile, as the prototype's `.tile`: a glyph at the top, the name (and a [state] line) at the bottom, on
 * the veil, at least 96 tall. [quiet] greys the glyph and the state, for an input with nothing plugged
 * in. Pressed, it takes a brighter veil and dips to 0.97; a tap pulses it as the haptic plays.
 */
@Composable
private fun Tile(
    @DrawableRes icon: Int,
    name: String,
    state: String?,
    quiet: Boolean,
    onClickLabel: String,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        tween(160, easing = Motion.EaseOut),
        label = "tile press",
    )
    val fill by animateColorAsState(
        if (pressed) Palette.Veil2 else Palette.Veil,
        tween(160, easing = Motion.EaseOut),
        label = "tile fill",
    )
    val pulse = remember { Pulse() }

    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = 96.dp)
            .graphicsLayer {
                val s = minOf(press, pulse.scale)
                scaleX = s
                scaleY = s
                alpha = pulse.alpha
            }
            .focusRing(focused, TileShape)
            .clip(TileShape)
            .background(fill)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = onClickLabel) {
                scope.launch { pulse.play(reduceMotion) }
                onClick()
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S3),
    ) {
        Glyph(icon, 24.dp, if (quiet) Palette.Text3 else Palette.Text)
        // Without a state line the name sits at the bottom, as the prototype's. With one, the text is
        // what sets the height anyway, and keeping it under the glyph holds the names level across the
        // row when "Not connected" wraps on a narrow phone.
        Column(Modifier.weight(1f), verticalArrangement = if (state == null) Arrangement.Bottom else Arrangement.Top) {
            BasicText(name, style = Strong)
            if (state != null) {
                Spacer(Modifier.height(2.dp))
                BasicText(state, style = Type.Caption.copy(color = if (quiet) Palette.Text3 else Palette.Text2))
            }
        }
    }
}
