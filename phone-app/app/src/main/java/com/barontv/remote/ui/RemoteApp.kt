package com.barontv.remote.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.tv.TvState
import com.barontv.remote.ui.apps.AppsTab
import com.barontv.remote.ui.apps.TvApps
import com.barontv.remote.ui.playing.PlayingTab
import com.barontv.remote.ui.tvtab.TvInputs
import com.barontv.remote.ui.tvtab.TvTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a notice stays up, as the prototype's status line. */
private const val NOTICE_MS = 1600L

/**
 * The whole app, as the prototype's phone: the TV's name and state at the top, the chosen tab under it,
 * and the tab bar at the bottom (Remote, Apps, Playing, TV), edge to edge on black. The tab chosen last
 * comes back at the next launch, and Back from any other tab goes to the Remote first.
 *
 * [remote] sends the keys and says whether the TV is there; [bridge] is the TV's new home, for its apps,
 * inputs, dock and what's playing.
 */
@Composable
fun RemoteApp(remote: TvRemote, bridge: TvBridge) {
    val context = LocalContext.current
    val memory = remember(context) { TabMemory(context) }
    var tab by rememberSaveable { mutableStateOf(memory.load()) }
    // What the TV said last, kept here so a tab opens on it instead of flashing empty.
    val apps = remember(bridge) { TvApps(bridge) }
    val inputs = remember(bridge) { TvInputs(bridge) }
    val choose = { next: Tab ->
        tab = next
        memory.save(next)
    }
    LightBarsOnBlack()
    BackHandler(enabled = tab != Tab.Remote) { choose(Tab.Remote) }
    Shell(remote = remote, bridge = bridge, apps = apps, inputs = inputs, tab = tab, onTab = choose)
}

/**
 * The shell with its tabs wired in: [tab] shows under the header, and the tab bar asks for another
 * through [onTab]. Tapping the chosen tab again scrolls it back to the top, as the prototype does.
 */
@Composable
internal fun Shell(
    remote: TvRemote,
    bridge: TvBridge,
    apps: TvApps,
    inputs: TvInputs,
    tab: Tab,
    onTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by remote.state.collectAsState()
    val notices = remember { Notices() }
    var reselected by remember { mutableIntStateOf(0) }
    KeepListsFresh(bridge, tab, apps, inputs)

    ShellFrame(
        remote = remote,
        tab = tab,
        onTab = { if (it == tab) reselected++ else onTab(it) },
        notices = notices,
        modifier = modifier,
    ) { content ->
        // No transition between tabs, as in the prototype: the new one is simply there.
        when (tab) {
            Tab.Remote -> RemoteTab(remote, bridge, content)
            Tab.Apps -> AppsTab(
                apps = apps,
                bridge = bridge,
                connected = state is TvState.Connected,
                say = notices::say,
                reselected = reselected,
                modifier = content,
            )
            Tab.Playing -> PlayingTab(bridge, content)
            Tab.Tv -> TvTab(
                apps = apps,
                inputs = inputs,
                bridge = bridge,
                say = notices::say,
                reselected = reselected,
                modifier = content,
            )
        }
    }
}

/** The system bars and any camera cutout: the shell keeps everything clear of them. */
private val Bars: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * The frame round every tab: the header at the top, clear of the status bar; the tab's [content] in
 * between, given the whole space; the line that says what just went wrong, just above the tabs; and the
 * tab bar, clear of the navigation bar.
 *
 * The content isn't clipped and the header draws over it, so a tab can paint behind the header and the
 * status bar (the prototype's Now playing glow rises from the top of the screen). The tab bar is solid
 * black, so nothing shows through it.
 */
@Composable
internal fun ShellFrame(
    remote: TvRemote,
    tab: Tab,
    onTab: (Tab) -> Unit,
    notices: Notices,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val state by remote.state.collectAsState()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val reduceMotion = rememberReduceMotion()
    val bars = Bars

    Column(
        modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
    ) {
        Header(
            state = state,
            reduceMotion = reduceMotion,
            haptics = haptics,
            modifier = Modifier
                .zIndex(1f)
                .windowInsetsPadding(bars.only(WindowInsetsSides.Top)),
            onRetry = remote::retry,
            onOpenWifiSettings = { openWifiSettings(context) },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // The header and the tab bar already keep this space clear of the bars.
                .consumeWindowInsets(bars),
        ) {
            content(Modifier.fillMaxSize())
            NoticeLine(
                notices = notices,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = Space.S6, end = Space.S6, bottom = Space.S5),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.Bg)
                .windowInsetsPadding(bars.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.TopCenter,
        ) {
            TabBar(chosen = tab, onSelect = onTab, haptics = haptics, reduceMotion = reduceMotion)
        }
    }
}

/**
 * The prototype's status line, kept for what the TV couldn't do (an app that didn't open, an order the TV
 * didn't take): a dark pill just above the tabs for 1.6 s. Successes need no line: the TV shows them.
 */
@Stable
internal class Notices {
    var text by mutableStateOf("")
        private set
    var shown by mutableStateOf(false)
        internal set
    var serial by mutableIntStateOf(0)
        private set

    fun say(line: String) {
        text = line
        shown = true
        serial++
    }
}

@Composable
private fun NoticeLine(notices: Notices, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    // Each new line restarts the clock.
    LaunchedEffect(notices.serial) {
        if (notices.serial > 0) {
            delay(NOTICE_MS)
            notices.shown = false
        }
    }
    // It fades in rising 6 dp, and out the same way; with reduced motion it only fades.
    val rise = with(LocalDensity.current) { 6.dp.roundToPx() }
    val fade = tween<Float>(180, easing = Motion.EaseOut)
    val slide = tween<IntOffset>(180, easing = Motion.EaseOut)
    AnimatedVisibility(
        visible = notices.shown,
        modifier = modifier,
        enter = fadeIn(fade) + if (reduceMotion) EnterTransition.None else slideInVertically(slide) { rise },
        exit = fadeOut(fade) + if (reduceMotion) ExitTransition.None else slideOutVertically(slide) { rise },
    ) {
        BasicText(
            notices.text,
            style = Type.Body.copy(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(Palette.Panel, CircleShape)
                .padding(horizontal = Space.S4, vertical = 9.dp),
        )
    }
}

/**
 * Asks the TV's new home for its apps and inputs whenever it starts answering, on every visit to the tab
 * that shows them, and when the app comes back on screen; the first answer is fetched whatever the tab,
 * so the Apps and TV tabs open on real lists.
 *
 * It watches the lifecycle with a plain observer rather than `repeatOnLifecycle`, which needs
 * `Dispatchers.Main`: Paparazzi has none, and the failure would leak into later tests.
 */
@Composable
private fun KeepListsFresh(bridge: TvBridge, tab: Tab, apps: TvApps, inputs: TvInputs) {
    val answering by bridge.available.collectAsState()
    val scope = rememberCoroutineScope()
    val shown by rememberUpdatedState(tab)

    suspend fun refresh(on: Tab) {
        // Read when it runs: a delegated state, so this is the latest answer, not the one at composition.
        if (!answering) return
        if (on == Tab.Apps || on == Tab.Tv || apps.list == null) apps.refresh()
        if (on == Tab.Tv || inputs.list == null) inputs.refresh()
    }

    LaunchedEffect(answering, tab, apps, inputs) {
        refresh(tab)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, apps, inputs) {
        // The first start is the one the effect above already covers.
        var seen = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (seen) scope.launch { refresh(shown) }
                seen = true
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** The tab chosen last, kept across launches. */
private class TabMemory(context: Context) {
    private val prefs = context.getSharedPreferences("shell", Context.MODE_PRIVATE)

    fun load(): Tab = Tab.named(prefs.getString(KEY, null))

    fun save(tab: Tab) = prefs.edit { putString(KEY, tab.name) }

    private companion object {
        const val KEY = "tab"
    }
}

/**
 * The screen is always black, so the status and navigation bars always want light icons and no scrim.
 * `enableEdgeToEdge()` picks them from the phone's light or dark mode, which in light mode would put dark
 * icons, and a white bar behind three-button navigation, over the black.
 */
@Composable
private fun LightBarsOnBlack() {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
