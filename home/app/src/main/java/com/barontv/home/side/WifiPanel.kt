package com.barontv.home.side

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wi-Fi (sidebar.css's Wi-Fi view): the header with the back chevron, the title and the switch; the
 * network in use as its own card (a green badge, its name over "Connected"); the rest under "Other
 * networks" in one grouped card split by hairlines, each with its lock and the signal fan with the arcs it
 * doesn't reach dimmed. Off, a quiet line stands where the networks were. Focus is colour only, except the
 * switch, whose colour is its state, so it gets a ring.
 *
 * It shows a [WifiUi]; WifiLink fills it on the TV, the snapshot tests with fixtures.
 */
internal class WifiPanel(context: Context) : ViewGroup(context) {

    private fun dp(v: Float) = context.dp(v)

    var onBack: () -> Unit = {}
    var onToggle: (Boolean) -> Unit = {}
    var onChoose: (Net) -> Unit = {}

    /** The network in use was chosen: the prototype just says how it is ("Connected to Home 5G"). */
    var onCurrent: (Net, Boolean) -> Unit = { _, _ -> }

    // After the switch is flipped here, older news from the worker can still be on its way; for a moment
    // the switch's own state wins.
    private var toggledAt = Long.MIN_VALUE / 2

    val back = SvBack(context)
    val switch = SvSwitch(context)
    val hero = SvHero(context)
    private val group = SvGroup(context)
    private val glyphs = NetGlyphs(context)

    private val title = context.getString(R.string.side_wifi)
    private val titlePaint = Type.paint(context, Fonts.semibold(context), 15f, Tokens.TEXT, -0.006f)
    private val labelPaint = Type.paint(context, Fonts.semibold(context), 12f, Tokens.TEXT_2, 0.012f)
    private val quietPaint = Type.paint(context, Fonts.regular(context), 12f, Tokens.TEXT_2)

    var ui = WifiUi(enabled = true)
        private set
    private var label = ""
    private var labelTop = 0f
    private var quiet: List<String> = emptyList()
    private var quietTop = 0f

    init {
        clipChildren = false
        setWillNotDraw(false)
        back.id = generateViewId()
        switch.id = generateViewId()
        hero.id = generateViewId()
        // Up from the network in use reaches the back chevron, as the prototype's order has it; Up from the
        // header goes nowhere (the system would jump to the island, which the prototype doesn't).
        hero.nextFocusUpId = back.id
        back.nextFocusUpId = back.id
        switch.nextFocusUpId = switch.id
        back.setOnClickListener { onBack() }
        switch.setOnClickListener {
            val on = !switch.on
            // Off, the list gives way to the quiet line at once; on, it looks for networks.
            render(if (on) WifiUi(enabled = true, scanning = true) else WifiUi(enabled = false), animate = true, local = true)
            toggledAt = SystemClock.uptimeMillis()
            onToggle(on)
        }
        hero.setOnClickListener { ui.current?.let { onCurrent(it, ui.joining) } }
        addView(back)
        addView(switch)
        addView(hero)
        addView(group)
    }

    /**
     * Shows [news], keeping focus on the same network when the list reorders. News from the worker that
     * contradicts a switch flipped here a moment ago is older than the flip, so it waits; [local] news is the flip.
     */
    fun render(news: WifiUi, animate: Boolean, local: Boolean = false) {
        val stale = !local && news.enabled != switch.on && SystemClock.uptimeMillis() - toggledAt < SETTLE_MS
        val next = if (stale) ui else news
        val focusedNet = (group.list.focusedChild as? SvNet)?.net?.ssid
        val heroHadFocus = hero.isFocused
        // Rows and the hero may go: focus waits on the switch meanwhile, so it never drops out of the view
        // (which would close it).
        if (focusedNet != null || heroHadFocus) switch.requestFocus()
        ui = next
        switch.set(next.enabled, animate)
        quiet = emptyList()
        if (!next.enabled) {
            hero.visibility = GONE
            group.visibility = GONE
            label = ""
            quiet = wrap(context.getString(R.string.side_wifi_off))
        } else {
            val current = next.current
            hero.visibility = if (current != null) VISIBLE else GONE
            if (current != null) hero.bind(current, next.joining, glyphs)
            label = context.getString(if (current != null) R.string.side_other_networks else R.string.side_networks)
            group.bind(next.others, glyphs, onChoose)
            group.visibility = if (next.others.isEmpty()) GONE else VISIBLE
            if (next.others.isEmpty()) {
                quiet = wrap(context.getString(if (next.scanning) R.string.side_looking else R.string.side_none_nearby))
            }
            group.firstUp(if (current != null) hero.id else back.id)
        }
        requestLayout()
        invalidate()
        // Focus follows its network, or settles somewhere sensible if what held it has gone.
        if (focusedNet != null) {
            group.rowFor(focusedNet)?.requestFocus() ?: focusFirst()
        } else if (heroHadFocus) {
            focusFirst()
        }
    }

    /** True once a real reading has been shown, so reopening starts from it instead of the placeholder. */
    val hasReading: Boolean get() = readings > 0
    private var readings = 0

    /** Shows what WifiLink read; the first reading after opening lands without animating the switch. */
    fun reading(news: WifiUi, first: Boolean) {
        readings++
        render(news, animate = !first)
    }

    /** The first row under the header: the network in use, else the first network, else the switch. */
    fun focusFirst() {
        when {
            hero.visibility == VISIBLE -> hero.requestFocus()
            group.visibility == VISIBLE && group.list.childCount > 0 -> group.list.getChildAt(0).requestFocus()
            else -> switch.requestFocus()
        }
    }

    /** The quiet line, wrapped at spaces to the card's width less its 8 dp padding. */
    private fun wrap(text: String): List<String> {
        val room = dp(222f - 16f)
        val lines = ArrayList<String>(3)
        var line = ""
        for (word in text.split(' ')) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && quietPaint.measureText(longer) > room) {
                lines.add(line)
                line = word
            } else {
                line = longer
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        measureExactly(back, dp(30f), dp(30f))
        measureExactly(switch, dp(52f), dp(34f))
        measureExactly(hero, w - dp(10f), dp(52f))
        // The card is as tall as its rows, or what's left above the dock's bottom padding, when that's less.
        val top = groupTop()
        val rows = group.list.childCount
        val want = rows * dp(36f) + dp(6f)
        val room = h - dp(5f) - top
        measureExactly(group, w - dp(10f), min(want, room).coerceAtLeast(0f))
    }

    private fun measureExactly(v: View, w: Float, h: Float) {
        v.measure(MeasureSpec.makeMeasureSpec(w.roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.roundToInt(), MeasureSpec.EXACTLY))
    }

    // From the top: 8 dp, the header's 34 (4 + the 30 dp chevron), 8; the hero's 52 and 8 when there is one;
    // the label's 19 (4 + its 15 dp line) and 8; then the card.
    private fun contentTop() = dp(8f + 34f + 8f)

    private fun groupTop(): Float {
        labelTop = contentTop() + if (hero.visibility == VISIBLE) dp(52f + 8f) else 0f
        return labelTop + dp(19f + 8f)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(back, dp(9f), dp(12f))
        // The switch's view is 4 dp bigger all round than the switch, to hold its focus ring.
        place(switch, (r - l) - dp(5f + 4f + 44f + 4f), dp(10f))
        place(hero, dp(5f), contentTop())
        val top = groupTop()
        place(group, dp(5f), top)
        quietTop = if (!ui.enabled) contentTop() else top
    }

    private fun place(v: View, x: Float, y: Float) {
        val l = x.roundToInt()
        val t = y.roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // The title, centred on the chevron's middle line.
        canvas.drawText(title, dp(47f), dp(32.5f), titlePaint)
        if (label.isNotEmpty()) canvas.drawText(label, dp(13f), labelTop + dp(16f), labelPaint)
        // The quiet line: 8 dp in, 12 dp type on 17.4 dp lines.
        var y = quietTop + dp(8f) + dp(Type.baseline(17.4f, 12f))
        for (line in quiet) {
            canvas.drawText(line, dp(13f), y, quietPaint)
            y += dp(17.4f)
        }
    }

    private companion object {
        const val SETTLE_MS = 800L
    }
}

/** The glyphs every network row shares, drawn at each row's place by moving the canvas, not the glyph. */
internal class NetGlyphs(context: Context) {
    private val signal = context.dp(16f).roundToInt()
    private val lockSize = context.dp(13f).roundToInt()
    val lock: Drawable = context.glyph(R.drawable.ph_lock_simple, Tokens.TEXT_2).apply { setBounds(0, 0, lockSize, lockSize) }

    /** The whole fan, dimmed: the arcs a network doesn't reach. */
    val fan: Drawable = context.glyph(R.drawable.ph_wifi_high, Tokens.TEXT).apply {
        setBounds(0, 0, signal, signal)
        alpha = (255 * 0.26f).roundToInt()
    }
    private val strengths = intArrayOf(R.drawable.ph_wifi_low, R.drawable.ph_wifi_medium, R.drawable.ph_wifi_high)
    private val drawn = Array(3) { context.glyph(strengths[it], Tokens.TEXT).apply { setBounds(0, 0, signal, signal) } }

    fun strength(res: Int): Drawable = drawn[strengths.indexOf(res).coerceAtLeast(0)]
}

/** The back chevron: a 30 dp circle in the rows' colour, lighter when focused. */
internal class SvBack(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevron = context.glyph(R.drawable.ph_caret_left, Tokens.TEXT)
    private val fade = FocusFade(this)

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
        contentDescription = context.getString(R.string.side_back)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = context.dp(16f).roundToInt()
        chevron.setBounds((w - g) / 2, (h - g) / 2, (w - g) / 2 + g, (h - g) / 2 + g)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fade.to(gainFocus)
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = SideColors.mix(SideColors.ROW, SideColors.ROW_FOCUS, fade.value)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, fill)
        chevron.draw(canvas)
    }
}

/** The iOS-style switch: green when on; its colour is its state, so focus also draws a ring round it. */
internal class SvSwitch(context: Context) : View(context) {
    private fun dp(v: Float) = context.dp(v)

    var on = true
        private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SideColors.KNOB }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = SideColors.RING
    }
    private val rect = RectF()
    private val fade = FocusFade(this)
    private var knobAt = 1f
    private var knobFrom = 1f
    private val slide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            knobAt = knobFrom + ((if (on) 1f else 0f) - knobFrom) * it.animatedFraction
            invalidate()
        }
    }
    private var colourFrom = SideColors.GREEN
    private val recolour = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200
        interpolator = Tokens.EASE_OUT
        addUpdateListener { invalidate() }
    }

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
        contentDescription = context.getString(R.string.side_wifi)
    }

    fun set(value: Boolean, animate: Boolean) {
        if (value == on && !slide.isRunning) {
            knobAt = if (on) 1f else 0f
            return
        }
        colourFrom = trackColour()
        on = value
        knobFrom = knobAt
        slide.cancel()
        recolour.cancel()
        if (animate && isAttachedToWindow) {
            slide.start()
            recolour.start()
        } else {
            knobAt = if (on) 1f else 0f
        }
        invalidate()
    }

    private fun trackColour(): Int = if (on) {
        SideColors.mix(SideColors.GREEN, SideColors.GREEN_FOCUS, fade.value)
    } else {
        SideColors.mix(SideColors.SWITCH_OFF, SideColors.SWITCH_OFF_FOCUS, fade.value)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fade.to(gainFocus)
    }

    override fun onDraw(canvas: Canvas) {
        val m = dp(4f)
        rect.set(m, m, width - m, height - m)
        val target = trackColour()
        track.color = if (recolour.isRunning) SideColors.mix(colourFrom, target, recolour.animatedFraction) else target
        canvas.drawRoundRect(rect, dp(13f), dp(13f), track)
        if (isFocused) {
            // 2 dp of white, 2 dp outside the switch.
            rect.inset(-dp(3f), -dp(3f))
            canvas.drawRoundRect(rect, dp(16f), dp(16f), ring)
        }
        canvas.drawCircle(m + dp(3f + 10f) + dp(18f) * knobAt, height / 2f, dp(10f), knob)
    }
}

/** The network in use: a filled badge with its strength, the name over "Connected" (or "Connecting…"). */
internal class SvHero(context: Context) : View(context) {
    private fun dp(v: Float) = context.dp(v)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = Type.paint(context, Fonts.semibold(context), 13f, Tokens.TEXT)
    private val notePaint = Type.paint(context, Fonts.medium(context), 12f, Tokens.CONNECTED_GREEN)
    private val rect = RectF()
    private val fade = FocusFade(this)
    private val strengths = intArrayOf(R.drawable.ph_wifi_low, R.drawable.ph_wifi_medium, R.drawable.ph_wifi_high)
    private val badgeGlyphs = Array(3) { context.glyph(strengths[it], SideColors.BADGE_GLYPH) }
    private var glyph = 2
    private var name = ""
    private var note = ""
    private var joining = false
    private var breatheStart = 0L
    private val breathe = Choreographer.FrameCallback { invalidate() }
    private val connected = context.getString(R.string.side_connected)
    private val connecting = context.getString(R.string.side_connecting)

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
    }

    fun bind(net: Net, joining: Boolean, glyphs: NetGlyphs) {
        glyph = strengths.indexOf(WifiMath.glyph(net.rssi)).coerceAtLeast(0)
        if (joining && !this.joining) breatheStart = AnimationUtils.currentAnimationTimeMillis()
        this.joining = joining
        name = TextUtils.ellipsize(net.ssid, namePaint, dp(158f), TextUtils.TruncateAt.END).toString()
        note = if (joining) connecting else connected
        notePaint.color = if (joining) Tokens.TEXT_2 else Tokens.CONNECTED_GREEN
        contentDescription = "${net.ssid}, $note"
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = dp(18f).roundToInt()
        val l = (dp(10f + 16f) - g / 2f).roundToInt()
        val t = (h / 2f - g / 2f).roundToInt()
        for (d in badgeGlyphs) d.setBounds(l, t, l + g, t + g)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fade.to(gainFocus)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(breathe)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = SideColors.mix(SideColors.ROW, SideColors.ROW_FOCUS, fade.value)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fill)
        badge.color = if (joining) breathing() else SideColors.GREEN
        canvas.drawCircle(dp(10f + 16f), height / 2f, dp(16f), badge)
        badgeGlyphs[glyph].draw(canvas)
        canvas.drawText(name, dp(52f), dp(23f), namePaint)
        canvas.drawText(note, dp(52f), dp(39f), notePaint)
        if (joining && isShown) {
            // The badge breathes until it's connected; 30 frames a second is plenty for a slow fade.
            Choreographer.getInstance().removeFrameCallback(breathe)
            Choreographer.getInstance().postFrameCallbackDelayed(breathe, 17)
        }
    }

    /** sv-joining: oklch 34% to 46% and back, a second each way, eased out. */
    private fun breathing(): Int {
        val t = (AnimationUtils.currentAnimationTimeMillis() - breatheStart) % 2000L
        val directed = if (t < 1000L) t / 1000f else 2f - t / 1000f
        return SideColors.mix(SideColors.SWITCH_OFF, SideColors.JOINING_PEAK, Tokens.EASE_OUT.getInterpolation(directed))
    }
}

/** Other networks: one grouped card (padding 3, radius 14), its rows scrolling inside when there are many. */
internal class SvGroup(context: Context) : FrameLayout(context) {
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    init {
        val pad = context.dp(3f).roundToInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(SideColors.ROW)
            cornerRadius = context.dp(14f)
        }
        // The rows scroll under the card's rounded corners, clipped to them.
        clipToOutline = true
        scroll.addView(list)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(nets: List<Net>, glyphs: NetGlyphs, onChoose: (Net) -> Unit) {
        while (list.childCount > nets.size) list.removeViewAt(list.childCount - 1)
        while (list.childCount < nets.size) {
            list.addView(SvNet(context), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(36f).roundToInt()))
        }
        for (i in nets.indices) {
            val row = list.getChildAt(i) as SvNet
            row.bind(nets[i], first = i == 0, glyphs)
            row.setOnClickListener { onChoose(row.net ?: return@setOnClickListener) }
        }
    }

    /** Where Up from the first network goes: the network in use, or the chevron. */
    fun firstUp(id: Int) {
        if (list.childCount > 0) list.getChildAt(0).nextFocusUpId = id
    }

    fun rowFor(ssid: String): View? {
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as SvNet
            if (row.net?.ssid == ssid) return row
        }
        return null
    }
}

/** One of the other networks: its name, its lock if it has a password, the signal fan. */
internal class SvNet(context: Context) : View(context) {
    private fun dp(v: Float) = context.dp(v)

    var net: Net? = null
        private set
    private var first = true
    private var name = ""
    private lateinit var glyphs: NetGlyphs
    private var strength: Drawable? = null
    private val namePaint: TextPaint = Type.paint(context, Fonts.medium(context), 13f, Tokens.TEXT)
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint().apply { color = SideColors.HAIRLINE }
    private val rect = RectF()
    private val fade = FocusFade(this)

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        isClickable = true
    }

    fun bind(n: Net, first: Boolean, glyphs: NetGlyphs) {
        this.glyphs = glyphs
        net = n
        this.first = first
        strength = glyphs.strength(WifiMath.glyph(n.rssi))
        // The name runs up to the lock (or the signal, without one), 10 dp short of it.
        val room = dp(216f - 11f - 16f - 10f - 11f) - if (n.locked) dp(13f + 10f) else 0f
        name = TextUtils.ellipsize(n.ssid, namePaint, room, TextUtils.TruncateAt.END).toString()
        contentDescription = n.ssid
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        fade.to(gainFocus)
        // The hairline under this row belongs to the next one, which hides it next to focus.
        val parent = parent as? ViewGroup ?: return
        parent.getChildAt(parent.indexOfChild(this) + 1)?.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (fade.value > 0f) {
            rect.set(0f, 0f, w, h)
            focusFill.color = SideColors.ROW_FOCUS
            focusFill.alpha = (255 * fade.value).roundToInt()
            canvas.drawRoundRect(rect, dp(11f), dp(11f), focusFill)
        }
        if (!first && !isFocused && !previousFocused()) canvas.drawRect(dp(11f), 0f, w - dp(11f), dp(1f), hairline)
        canvas.drawText(name, dp(11f), dp(23f), namePaint)
        // The trail, from the right: the signal 11 dp in, the lock 10 dp before it.
        val sx = w - dp(11f + 16f)
        canvas.save()
        canvas.translate(sx, dp(10f))
        glyphs.fan.draw(canvas)
        strength?.draw(canvas)
        canvas.restore()
        if (net?.locked == true) {
            canvas.save()
            canvas.translate(sx - dp(10f + 13f), dp(11.5f))
            glyphs.lock.draw(canvas)
            canvas.restore()
        }
    }

    private fun previousFocused(): Boolean {
        val parent = parent as? ViewGroup ?: return false
        return parent.getChildAt(parent.indexOfChild(this) - 1)?.isFocused == true
    }
}

/** The 200 ms colour fade every row, the chevron and the switch use on focus (sidebar.css's .sv-item). */
internal class FocusFade(private val view: View) {
    var value = 0f
        private set
    private var from = 0f
    private var target = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200
        interpolator = Tokens.EASE_OUT
        addUpdateListener {
            value = from + (target - from) * it.animatedFraction
            view.invalidate()
        }
    }

    fun to(focused: Boolean) {
        from = value
        target = if (focused) 1f else 0f
        anim.cancel()
        if (view.isAttachedToWindow) anim.start() else value = target
        view.invalidate()
    }
}
