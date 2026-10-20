package com.barontv.remote.ui.playing

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.barontv.remote.tv.MediaAction
import com.barontv.remote.tv.NowPlaying
import com.barontv.remote.ui.Haptics
import com.barontv.remote.ui.Motion
import com.barontv.remote.ui.Palette
import com.barontv.remote.ui.Pill
import com.barontv.remote.ui.Space
import com.barontv.remote.ui.Type
import com.barontv.remote.ui.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The cover, the text and the controls share one column, as wide as the prototype's (312 dp) at most. */
private val ColumnWidth = 312.dp

/** Below this height the gaps tighten (a Nexus 5), and the cover gives way before anything else does. */
private val CompactBelow = 560.dp

/** The cover never gets shorter than this; past it, the tab scrolls instead. */
private val SmallestCover = 96.dp

/** The column follows the cover's width, but never narrower than this, so the controls keep their room. */
private val NarrowestColumn = 260.dp

/** Wider than tall, and this wide, the cover goes beside the controls (a phone turned on its side). */
private val SideBySideFrom = 560.dp

/** The text block: a 28 dp title (or a 34 dp app name), 2 dp, a 20 dp artist line. */
private val SongTextHeight = 50.dp
private val AppTextHeight = 56.dp

private const val NO_BRIDGE = "The TV's new home isn't answering. What's playing comes from it."

/**
 * The Playing tab's content, one of four states, cross-fading between them:
 *
 * - Something playing or paused: the app's cover, the song (or the app's name), the progress line when
 *   the TV knows the times, previous, play or pause and next, and the TV's volume, with the cover's
 *   colour glowing behind.
 * - Nothing playing: a calm note and a button that opens Spotify on the TV.
 * - No bridge: one quiet line, since what's playing comes from the TV's new home.
 * - Before the first answer: nothing, so no state flashes past.
 */
@Composable
internal fun PlayingScreen(
    ui: PlayingUi,
    controls: PlayingControls,
    modifier: Modifier = Modifier,
    previewHeld: Boolean = false,
) {
    val haptics = rememberHaptics()
    val lessMotion = rememberLessMotion()
    AnimatedContent(
        targetState = ui,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { fadeIn(tween(300, easing = Motion.EaseOut)) togetherWith fadeOut(tween(150)) },
        // A new song or a new level is the same page; only a change of state cross-fades.
        contentKey = { it::class },
        label = "playing state",
    ) { shown ->
        when (shown) {
            PlayingUi.Waiting -> Box(Modifier.fillMaxSize())
            PlayingUi.NoBridge -> QuietLine(NO_BRIDGE)
            PlayingUi.Idle -> NothingPlaying(controls, haptics)
            is PlayingUi.Playing -> Player(shown, controls, haptics, lessMotion, previewHeld)
        }
    }
}

/**
 * The player. Upright it is the prototype's column, centred in the height, with the prototype's gaps on a
 * tall phone and tighter ones on a short one, where the cover shrinks to make room. On its side, the cover
 * goes left of the rest. Whatever still doesn't fit scrolls.
 */
@Composable
private fun Player(
    state: PlayingUi.Playing,
    controls: PlayingControls,
    haptics: Haptics,
    lessMotion: Boolean,
    previewHeld: Boolean,
) {
    val now = state.now
    val look = remember(now.packageName) { lookFor(now.packageName) }
    val appName = nameOf(now)
    val song = now.title.isNotBlank()
    val timed = now.durationMs > 0 && now.positionMs >= 0
    val scope = rememberCoroutineScope()

    // Where the cover sits, for the glow behind it, in the tab's own coordinates.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var coverInRoot by remember { mutableStateOf<Rect?>(null) }
    val coverPlaced = Modifier.onGloballyPositioned { coverInRoot = it.boundsInRoot() }

    val onPrevious = {
        haptics.tap()
        scope.launch { if (!controls.skip(MediaAction.PREVIOUS)) haptics.reject() }
        Unit
    }
    val onPlayPause = {
        haptics.tap()
        scope.launch { if (!controls.playPause()) haptics.reject() }
        Unit
    }
    val onNext = {
        haptics.tap()
        scope.launch { if (!controls.skip(MediaAction.NEXT)) haptics.reject() }
        Unit
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        Glow(look.vivid, { coverInRoot?.translate(-origin) }, Modifier.matchParentSize())

        val rhythm = if (maxHeight < CompactBelow) Rhythm.Compact else Rhythm.Tall
        val textHeight = if (song) SongTextHeight else AppTextHeight
        val sideBySide = maxWidth > maxHeight && maxWidth >= SideBySideFrom

        if (sideBySide) {
            val column = min(ColumnWidth, (maxWidth - Space.Edge * 2 - Space.S8) / 2)
            Row(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Space.Edge, vertical = Space.S3),
                horizontalArrangement = Arrangement.spacedBy(Space.S8, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(look, appName, paused = !now.playing, lessMotion, column, coverPlaced)
                Column(Modifier.width(column), horizontalAlignment = Alignment.CenterHorizontally) {
                    NowText(now, appName, column)
                    if (timed) {
                        Spacer(Modifier.height(Rhythm.Compact.textToProgress))
                        ProgressLine(now, column)
                        Spacer(Modifier.height(Rhythm.Compact.progressToControls))
                    } else {
                        Spacer(Modifier.height(Rhythm.Compact.textToControls))
                    }
                    Transport(now.playing, column, onPrevious, onPlayPause, onNext)
                    Spacer(Modifier.height(Rhythm.Compact.controlsToVolume))
                    VolumeSlider(state.volume, controls, haptics, column, previewHeld = previewHeld)
                }
            }
        } else {
            val widest = min(ColumnWidth, maxWidth - Space.Edge * 2)
            // The cover takes the height the rest leaves, up to the column's width.
            fun coverWidth(source: Boolean): Dp {
                val room = (maxHeight - rhythm.around(textHeight, timed, source)) * 16f / 9f
                return room.coerceIn(min(SmallestCover * 16f / 9f, widest), widest)
            }
            // On a short phone the source line, which only repeats the cover's app, goes before the
            // cover gives up more than a tenth of its width.
            val source = song && coverWidth(source = true) >= widest * 0.9f
            val cover = coverWidth(source)
            // Everything lines up with the cover, as in the prototype, unless that would cramp the controls.
            val column = maxOf(cover, min(NarrowestColumn, widest))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(top = rhythm.top, bottom = rhythm.bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Cover(look, appName, paused = !now.playing, lessMotion, cover, coverPlaced)
                Spacer(Modifier.height(rhythm.coverToText))
                NowText(now, appName, column)
                if (timed) {
                    Spacer(Modifier.height(rhythm.textToProgress))
                    ProgressLine(now, column)
                    Spacer(Modifier.height(rhythm.progressToControls))
                } else {
                    Spacer(Modifier.height(rhythm.textToControls))
                }
                Transport(now.playing, column, onPrevious, onPlayPause, onNext)
                Spacer(Modifier.height(rhythm.controlsToVolume))
                VolumeSlider(state.volume, controls, haptics, column, previewHeld = previewHeld)
                if (source) {
                    Spacer(Modifier.height(rhythm.volumeToSource))
                    SourceLine(look, appName)
                }
            }
        }
    }
}

/**
 * The column's gaps. Tall is the prototype's: 12 above (the views' 8 and the view's 4), 28 under the
 * cover, 20 to the progress line, 12 to the controls, 16 to the volume, 20 to the source line and 32
 * below. Without times the controls sit 24 under the text. Compact tightens each for a short phone.
 */
private class Rhythm(
    val top: Dp,
    val coverToText: Dp,
    val textToProgress: Dp,
    val progressToControls: Dp,
    val textToControls: Dp,
    val controlsToVolume: Dp,
    val volumeToSource: Dp,
    val bottom: Dp,
) {
    /** Everything in the column but the cover. */
    fun around(textHeight: Dp, timed: Boolean, source: Boolean): Dp =
        top + coverToText + textHeight +
            (if (timed) textToProgress + ProgressHeight + progressToControls else textToControls) +
            ControlsHeight + controlsToVolume + VolumeHeight +
            (if (source) volumeToSource + SourceHeight else 0.dp) +
            bottom

    companion object {
        val Tall = Rhythm(
            top = Space.S3,
            coverToText = Space.S7,
            textToProgress = Space.S5,
            progressToControls = Space.S3,
            textToControls = Space.S6,
            controlsToVolume = Space.S4,
            volumeToSource = Space.S5,
            bottom = Space.S8,
        )
        val Compact = Rhythm(
            top = Space.S2,
            coverToText = Space.S5,
            textToProgress = Space.S4,
            progressToControls = Space.S2,
            textToControls = Space.S4,
            controlsToVolume = Space.S2,
            volumeToSource = Space.S3,
            bottom = Space.S4,
        )
    }
}

/**
 * Nothing playing: the tab's music notes on a veil, two calm lines and the one thing to do, open Spotify on
 * the TV. A press says it's opening for a moment (the tab moves on by itself once music plays); if the TV
 * didn't take it, the second line says so instead.
 */
@Composable
private fun NothingPlaying(controls: PlayingControls, haptics: Haptics) {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var missed by remember { mutableStateOf(false) }
    LaunchedEffect(opening, missed) {
        if (opening || missed) {
            delay(SAYS_FOR_MS)
            opening = false
            missed = false
        }
    }
    // Centred in the tab; on a phone on its side, where it may not fit, it scrolls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = Space.Edge, vertical = Space.S6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NothingPlayingContent(opening, missed) {
                scope.launch {
                    if (controls.openSpotify()) {
                        haptics.confirm()
                        missed = false
                        opening = true
                    } else {
                        haptics.reject()
                        opening = false
                        missed = true
                    }
                }
            }
        }
    }
}

@Composable
private fun NothingPlayingContent(opening: Boolean, missed: Boolean, onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        NotesMark()
        Spacer(Modifier.height(Space.S4))
        BasicText("Nothing's playing", style = Type.Headline.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(Space.S1))
        BasicText(
            // A no-break space keeps "the TV" on one line when it wraps.
            if (missed) "The TV didn't answer. Try again in a moment." else "Play something on the\u00A0TV and it shows up here.",
            style = Type.Body.copy(color = Palette.Text2, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading),
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(Space.S5))
        Pill(if (opening) "Opening Spotify…" else "Open Spotify", onClick = onOpen)
    }
}

/** How long "Opening Spotify" or "The TV didn't answer" stays before the empty state settles back. */
private const val SAYS_FOR_MS = 4_000L

/** One quiet line in the middle of the tab, balanced over two lines when it wraps. */
@Composable
private fun QuietLine(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.S6),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = Type.Body.copy(color = Palette.Text2, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading),
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

/** The app's name as the TV gave it, or a readable one from its package when it gave none. */
private fun nameOf(now: NowPlaying): String =
    now.appName.ifBlank { now.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
        .ifBlank { "The TV" }

/**
 * Whether the phone asks for less motion (Remove animations sets the animator scale to 0). Then the cover
 * stays its size when paused, as the prototype does with reduced motion.
 */
@Composable
private fun rememberLessMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
