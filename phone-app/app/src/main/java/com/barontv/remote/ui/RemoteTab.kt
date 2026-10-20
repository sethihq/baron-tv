package com.barontv.remote.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.tv.TvState
import com.barontv.remote.tv.VolumeStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * The Remote tab's content: the touch pad, and under it, near the thumb, the keys of the TV's own remote.
 * It goes between the app's shared header and its tab bar and fills whatever it's given, keeping its own
 * margins (20 dp at the sides; 12 above the pad and 32 under the keys on a tall phone). The app keeps it
 * clear of the system bars.
 *
 * It follows [remote] itself. While there's no TV the pad and keys dim, and the pad says what to do:
 * Try again, or the phone's Wi-Fi settings. While the home's bridge answers, Volume and Mute go through
 * [bridge], so the TV shows the home's own volume bar rather than CVTE's (see [RemoteKeys]).
 */
@Composable
fun RemoteTab(remote: TvRemote, bridge: TvBridge, modifier: Modifier = Modifier) {
    val state by remote.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keys = remember(remote, bridge) { RemoteKeys(remote, bridge) }
    LaunchedEffect(keys) { keys.run() }
    RemoteControls(
        state = state,
        onKey = keys::press,
        onRetry = remote::retry,
        onOpenWifiSettings = { openWifiSettings(context) },
        modifier = modifier,
    )
}

/**
 * Where the Remote tab's presses go. While the home's bridge answers, Volume and Mute go to it as volume
 * steps, so the TV shows the home's own volume bar instead of CVTE's pop-up; everything else, and volume
 * when there's no bridge, goes to [remote] as key presses (24, 25 and 164 for the volume keys).
 */
internal class RemoteKeys(private val remote: TvRemote, private val bridge: TvBridge) {
    // Steps for the bridge wait here and go one at a time, in order. A held key repeats every 160 ms and a
    // step answers in tens of milliseconds, so this only fills when the TV is slow; then new steps are
    // dropped, so the volume doesn't keep climbing after the finger has lifted.
    private val steps = Channel<VolumeStep>(capacity = 2)

    fun press(key: TvKey) {
        val step = volumeStepOf(key)
        if (step != null && bridge.available.value) steps.trySend(step) else remote.send(key)
    }

    /** Hands the bridge its volume steps, for as long as the tab is on screen. */
    suspend fun run() {
        for (step in steps) {
            try {
                bridge.changeVolume(step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // A bridge isn't meant to throw. If one ever does, the volume keys must keep working.
            }
        }
    }
}

/** The bridge's volume step for a volume key, or null for any other key. */
internal fun volumeStepOf(key: TvKey): VolumeStep? = when (key) {
    TvKey.VOLUME_UP -> VolumeStep.UP
    TvKey.VOLUME_DOWN -> VolumeStep.DOWN
    TvKey.MUTE -> VolumeStep.MUTE
    else -> null
}

/** How far the pad and keys fade while there's no TV: still readable, plainly asleep. */
private const val DIMMED = 0.45f

/**
 * The pad and the keys, stateless: they show [state] and report presses through [onKey], [onRetry] and
 * [onOpenWifiSettings]. While the TV isn't connected they dim and do nothing but play a gentle reject,
 * and the pad says what to do instead. Portrait puts the keys under the pad; a wide window (landscape on
 * a big phone, or a tablet) puts them beside it. [pressedEdge] is for snapshots (see [TouchPad]).
 */
@Composable
internal fun RemoteControls(
    state: TvState,
    onKey: (TvKey) -> Unit,
    onRetry: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    pressedEdge: TvKey? = null,
) {
    val haptics = rememberHaptics()
    val reduceMotion = rememberReduceMotion()
    val connected = state is TvState.Connected
    val dim by animateFloatAsState(if (connected) 1f else DIMMED, tween(300, easing = Motion.EaseOut), label = "dim")
    val notice = noticeFor(state)

    val pad = @Composable { padModifier: Modifier ->
        TouchPad(
            enabled = connected,
            dim = dim,
            reduceMotion = reduceMotion,
            haptics = haptics,
            onKey = onKey,
            modifier = padModifier,
            showHint = notice == null,
            pressedEdge = pressedEdge,
        ) {
            AnimatedContent(
                targetState = notice,
                transitionSpec = { fadeIn(tween(300, easing = Motion.EaseOut)) togetherWith fadeOut(tween(150)) },
                label = "pad notice",
            ) { shown ->
                if (shown != null) {
                    PadNotice(
                        notice = shown,
                        onAction = {
                            haptics.tap()
                            if (shown == Notice.NotFound) onRetry() else onOpenWifiSettings()
                        },
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val rhythm = Rhythm.forHeight(maxHeight)
        val sideBySide = maxWidth > maxHeight && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH

        if (sideBySide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Space.Edge)
                    .padding(top = rhythm.top, bottom = Space.S5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pad(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(Space.S8))
                KeyPad(connected, dim, haptics, onKey, rhythm.rowGap, Modifier.width(KEYS_WIDTH_BESIDE))
            }
        } else {
            // A window too short for the pad (split screen) scrolls instead of squeezing it.
            val short = maxHeight < SHORT_BELOW
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .then(if (short) Modifier else Modifier.weight(1f))
                        .widthIn(max = MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(horizontal = Space.Edge),
                ) {
                    Spacer(Modifier.height(rhythm.top))
                    pad(
                        Modifier
                            .fillMaxWidth()
                            .then(if (short) Modifier.height(PAD_MIN) else Modifier.weight(1f)),
                    )
                    Spacer(Modifier.height(rhythm.padToKeys))
                    KeyPad(connected, dim, haptics, onKey, rhythm.rowGap, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(rhythm.bottom))
                }
            }
        }
    }
}

/**
 * The vertical spacing. With room to spare it is the prototype's: 12 above the pad, 32 between the pad
 * and the keys, 24 between the two rows of keys, and 32 below them. A short phone (a Nexus 5, say)
 * tightens the gaps so the pad keeps a usable height instead.
 */
private class Rhythm(val top: Dp, val padToKeys: Dp, val rowGap: Dp, val bottom: Dp) {
    companion object {
        private val Tall = Rhythm(top = Space.S3, padToKeys = Space.S8, rowGap = Space.S6, bottom = Space.S8)
        private val Compact = Rhythm(top = Space.S2, padToKeys = Space.S6, rowGap = Space.S4, bottom = Space.S5)

        fun forHeight(height: Dp) = if (height < COMPACT_BELOW) Compact else Tall
    }
}

// Heights are the tab's own, without the header or the tab bar. The tall rhythm and the keys take 268 dp,
// so below 640 it would leave the pad under 372 and the compact one (236) takes over; below 400 even that
// would leave the pad under 164, so the tab scrolls with the pad at 200.
private val COMPACT_BELOW = 640.dp
private val SHORT_BELOW = 400.dp
private val PAD_MIN = 200.dp
private val MAX_WIDTH = 480.dp
private val SIDE_BY_SIDE_MIN_WIDTH = 600.dp
private val KEYS_WIDTH_BESIDE = 340.dp

/** What the pad says while there's no TV, and the one thing to do about it. */
private enum class Notice(val text: String, val action: String) {
    NotFound("Turn the TV on and keep this phone on the same Wi-Fi.", "Try again"),
    NoWifi("Join the TV's Wi-Fi to use the remote.", "Wi-Fi settings"),
}

private fun noticeFor(state: TvState): Notice? = when (state) {
    TvState.NotFound -> Notice.NotFound
    TvState.NoWifi -> Notice.NoWifi
    else -> null
}

/** One short line in the quieter grey, balanced over two lines when it wraps, and a pill under it. */
@Composable
private fun PadNotice(notice: Notice, onAction: () -> Unit) {
    Column(
        Modifier
            .padding(Space.S6)
            .widthIn(max = 280.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.S4),
    ) {
        BasicText(
            text = notice.text,
            style = Type.Body.copy(color = Palette.Text2, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading),
        )
        Pill(notice.action, onClick = onAction)
    }
}

/**
 * Opens the phone's Wi-Fi settings: the small panel over the app where the phone has it (Android 10 and
 * later), the full settings page before that.
 */
private fun openWifiSettings(context: Context) {
    val newTask = context.findActivity() == null
    val choices = buildList {
        if (Build.VERSION.SDK_INT >= 29) add(Intent(Settings.Panel.ACTION_WIFI))
        add(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    for (intent in choices) {
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // A trimmed-down phone without this screen: try the next one.
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
