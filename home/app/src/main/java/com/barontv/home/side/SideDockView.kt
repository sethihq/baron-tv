package com.barontv.home.side

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp

/**
 * The box on the right: Inputs (HDMI 1, HDMI 2, AV) and TV (Wi-Fi, Picture, Sound, Settings), whose Wi-Fi
 * and Sound tiles turn the whole box into their view, as a Control Centre module opens: the tiles slide out
 * to the left and fade, the view slides in from the right (sidebar.css's timings). Back, the chevron, or
 * focus leaving the box closes it again.
 *
 * Inputs are the TV's own (TvInputManager), switched the way the old launcher proved on this TV. Every
 * call into the system runs on one background thread, and every listener runs only while it's needed:
 * the inputs' while the home screen is in front, Wi-Fi's and the volume's while their view is open.
 */
@SuppressLint("ViewConstructor") // made in code by HomeActivity, which hands it the host
class SideDockView(context: Context, private val host: HomeHost) : FrameLayout(context) {

    private fun dp(v: Float) = context.dp(v)

    private val worker = SideWorker()
    private val home = SideHome(context)
    private val homeSlide = Slide(home)
    private val inputs = TvInputSource(context, worker)

    private var wifiPanel: WifiPanel? = null
    private var wifiSlide: Slide? = null
    private var wifiLink: WifiLink? = null
    private var soundBar: SoundBar? = null
    private var soundSlide: Slide? = null
    private var firstWifiFocus = false

    /** Off in the snapshot tests: the dock then never reaches for the TV's inputs, Wi-Fi or volume. */
    internal var useSystem = true

    /** What the box shows: [HOME], [WIFI] or [SOUND]. */
    private var showing = HOME
    private var openedFrom: View? = null

    // The frame: oklch 8% black with a hairline of white at 11%, its corners the tiles' 14 plus the 5 padding.
    private val frameFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.DOCK_FRAME }
    private val frameEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Tokens.FRAME_HAIRLINE
    }
    private val frame = RectF()
    private val frameRadius = dp(19f)

    // Leaving an open view (Left, to the shelf) closes it, as Control Centre's modules close when you look away.
    private val focusWatch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (showing != HOME && (now == null || !inside(now, openView()))) close(restoreFocus = false, animate = true)
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(home, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val (wifiTile, pictureTile, soundTile, settingsTile) = home.tvTiles
        wifiTile.bind(context.getString(R.string.side_wifi), R.drawable.ph_wifi_high, idle = false, live = false)
        pictureTile.bind(context.getString(R.string.side_picture), R.drawable.ph_image, idle = false, live = false)
        soundTile.bind(context.getString(R.string.side_sound), R.drawable.ph_speaker_high, idle = false, live = false)
        settingsTile.bind(context.getString(R.string.side_settings), R.drawable.ph_gear_six, idle = false, live = false)
        wifiTile.setOnClickListener { open(WIFI, wifiTile, animate = true) }
        soundTile.setOnClickListener { open(SOUND, soundTile, animate = true) }
        pictureTile.setOnClickListener { openPicture() }
        settingsTile.setOnClickListener { host.launch(Intent(Settings.ACTION_SETTINGS)) }
        showInputs(Inputs.BARON)
    }

    /** Back while a Wi-Fi or Sound view is open closes it; returns true if it did. */
    fun onBack(): Boolean {
        if (showing == HOME) return false
        close(restoreFocus = true, animate = true)
        return true
    }

    // ---- Inputs ----------------------------------------------------------------------------------------

    /** Puts [list] in the inputs row: a tile each, live, plain or dimmed by its state. */
    internal fun showInputs(list: List<TvInput>) {
        home.inputCount(list.size)
        list.forEachIndexed { i, input ->
            val tile = home.inputTiles[i]
            tile.bind(input.label, Inputs.glyph(input), Inputs.idle(input), Inputs.live(input))
            // A dimmed input still switches: the TV's state could be wrong, and then the button mustn't be dead.
            tile.setOnClickListener { inputs.switchTo(input, host) }
        }
    }

    private fun reloadInputs() {
        inputs.load { if (isAttachedToWindow) showInputs(it) }
    }

    // ---- Picture ----------------------------------------------------------------------------------------

    private fun openPicture() {
        worker.post {
            val intent = PictureSettings.find(context) ?: Intent(Settings.ACTION_SETTINGS)
            worker.toMain { host.launch(intent) }
        }
    }

    // ---- The views --------------------------------------------------------------------------------------

    private fun wifi(): WifiPanel = wifiPanel ?: WifiPanel(context).also { p ->
        hideAtStart(p)
        p.onBack = { close(restoreFocus = true, animate = true) }
        p.onToggle = { on ->
            host.toast(context.getString(if (on) R.string.side_wifi_on_toast else R.string.side_wifi_off_toast))
            wifiLink?.setEnabled(on)
        }
        p.onChoose = { net -> wifiLink?.choose(net) }
        p.onCurrent = { net, joining ->
            host.toast(context.getString(if (joining) R.string.side_connecting_to else R.string.side_connected_to, net.ssid))
        }
        addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wifiPanel = p
        wifiSlide = Slide(p)
        wifiLink = WifiLink(
            context,
            worker,
            onUi = { ui -> showWifi(ui) },
            onMessage = { host.toast(it) },
            openSettings = { host.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        )
    }

    private fun showWifi(ui: WifiUi) {
        val p = wifiPanel ?: return
        if (showing != WIFI) return
        p.reading(ui, first = firstWifiFocus)
        // The view opened before Wi-Fi's state was read: the first real news decides where focus starts.
        if (firstWifiFocus) {
            firstWifiFocus = false
            p.focusFirst()
        }
    }

    private fun sound(): SoundBar = soundBar ?: SoundBar(context).also { s ->
        s.worker = worker
        hideAtStart(s)
        addView(s, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        soundBar = s
        soundSlide = Slide(s)
    }

    /** A view waits out of sight, 14 dp to the right, until it's opened. */
    private fun hideAtStart(v: View) {
        v.visibility = INVISIBLE
        v.alpha = 0f
        v.translationX = dp(14f)
        if (v is ViewGroup) v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS else v.isFocusable = false
    }

    private fun openView(): View? = when (showing) {
        WIFI -> wifiPanel
        SOUND -> soundBar
        else -> null
    }

    private fun slideOf(which: Int): Slide? = if (which == WIFI) wifiSlide else soundSlide

    /** Turns the box into the Wi-Fi or Sound view, focus on its first row. */
    internal fun open(which: Int, from: View?, animate: Boolean) {
        if (showing != HOME) return
        val view: View = if (which == WIFI) wifi() else sound()
        showing = which
        openedFrom = from
        if (view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS else view.isFocusable = true
        view.visibility = VISIBLE
        val live = animate && useSystem
        // In: after 60 ms, opacity over 240 and into place over 280. Out: opacity 220, 14 dp left over 260.
        slideOf(which)?.to(1f, 0f, 240, 280, 60, hide = false, animate = live)
        homeSlide.to(0f, -dp(14f), 220, 260, 0, hide = true, animate = live)
        if (view is WifiPanel) {
            // Until Wi-Fi is read (a moment, inside the slide's 60 ms delay), the last reading, or a search.
            view.render(if (view.hasReading) view.ui.copy(scanning = true) else WifiUi(enabled = true, scanning = true), animate = false)
            view.focusFirst()
            firstWifiFocus = true
            if (useSystem) wifiLink?.start()
        } else if (view is SoundBar) {
            view.requestFocus()
            if (useSystem) view.start()
        }
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
    }

    /** Back to the tiles; with [restoreFocus], focus returns to the tile that opened the view. */
    internal fun close(restoreFocus: Boolean, animate: Boolean) {
        val view = openView() ?: return
        val which = showing
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
        showing = HOME
        firstWifiFocus = false
        if (view === wifiPanel) wifiLink?.stop()
        if (view === soundBar) soundBar?.stop()
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val live = animate && useSystem
        homeSlide.to(1f, 0f, 240, 280, 60, hide = false, animate = live)
        slideOf(which)?.to(0f, dp(14f), 220, 260, 0, hide = true, animate = live)
        if (restoreFocus) openedFrom?.requestFocus()
        if (view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS else view.isFocusable = false
        openedFrom = null
    }

    private fun inside(v: View, container: View?): Boolean {
        if (container == null) return false
        var p: Any? = v
        while (p is View) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    // ---- Life --------------------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker.start()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!useSystem) return
        if (visibility == VISIBLE) {
            // Back on the home screen: the inputs as they are now, and the open view's listening again.
            reloadInputs()
            inputs.listen { reloadInputs() }
            if (showing == WIFI) wifiLink?.start()
            if (showing == SOUND) soundBar?.start()
        } else {
            inputs.stopListening()
            wifiLink?.stop()
            soundBar?.stop()
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
        inputs.stopListening()
        wifiLink?.stop()
        soundBar?.stop()
        worker.stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val half = dp(0.5f)
        frame.set(half, half, w - half, h - half)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), frameRadius, frameRadius, frameFill)
        canvas.drawRoundRect(frame, frameRadius - dp(0.5f), frameRadius - dp(0.5f), frameEdge)
    }

    /** For snapshots: the view that's open, made if need be. */
    internal val wifiView: WifiPanel get() = wifi()
    internal val soundView: SoundBar get() = sound()
    internal val tiles: List<SideTile> get() = home.inputTiles + home.tvTiles

    internal companion object {
        const val HOME = 0
        const val WIFI = 1
        const val SOUND = 2
    }
}
