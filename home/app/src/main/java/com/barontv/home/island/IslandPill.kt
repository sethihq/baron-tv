package com.barontv.home.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import com.barontv.home.R
import com.barontv.home.core.Fonts
import com.barontv.home.core.NowPlaying
import com.barontv.home.core.Tokens
import com.barontv.home.core.dp
import com.barontv.home.media.NowPlayingControls
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The island itself, drawn in one view: the black pill with its hairline; compact, the cover, the song and
 * four bars in the cover's colour; open, the player (cover, song, artist, progress, times, previous / play
 * / next in Phosphor fill). The shape morphs and the two layers cross-fade with island.css's timings.
 *
 * It is one focusable view the size of the open island, so the system's focus search sees the island as a
 * whole (its focus rect is the pill as drawn right now); Left and Right walk the three controls inside.
 * What moves runs only while it can be seen: the bars while the pill is compact, shown and playing, the
 * times while it is open and playing.
 */
internal class IslandPill(context: Context) : View(context) {

    private fun dp(v: Float) = context.dp(v)

    // ---- Geometry (px), from island.css and the sidebar's 364 dp open island ---------------------------
    private val openW = dp(364f)
    private val openH = dp(104f)
    private val compactH = dp(34f)
    private val compactR = dp(17f)
    private val openR = dp(32f)
    private val hairline = dp(1f)
    private val infoW = dp(INFO_W_DP)

    // The compact row: 6 dp in, a 22 dp cover, 8, the song (168 dp at most), 8, the bars (19.5), 12 out.
    private val cLead = dp(6f)
    private val cArt = dp(22f)
    private val cGap = dp(8f)
    private val cTextMax = dp(168f)
    private val cBars = dp(19.5f)
    private val cTrail = dp(12f)

    // ---- Drawing tools, made once ------------------------------------------------------------------------
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.BG }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairline
        color = HAIRLINE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.TEXT }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val artSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artBigPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val emptyArt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.FILL }

    private val compactText = text(Fonts.medium(context), 13f, Tokens.TEXT, 0f)
    private val titleText = text(Fonts.semibold(context), 15f, Tokens.TEXT, -0.006f)
    private val artistText = text(Fonts.medium(context), 13f, Tokens.TEXT_2, 0f)
    private val timeText = text(Fonts.medium(context), 12f, Tokens.TEXT_2, 0.012f).apply {
        fontFeatureSettings = "'tnum'"
    }

    // The transport glyphs twice: light on black, and white on the focused control's accent.
    private val glyphs = arrayOf(R.drawable.ph_skip_back_fill, R.drawable.ph_play_fill, R.drawable.ph_pause_fill, R.drawable.ph_skip_forward_fill)
    private val glyphOff = Array(4) { glyph(glyphs[it], Tokens.TEXT) }
    private val glyphOn = Array(4) { glyph(glyphs[it], ON_ACCENT) }
    private val source = glyph(R.drawable.ph_spotify_logo, SPOTIFY)

    private val shapeRect = RectF()
    private val box = RectF()
    private val clip = Path()
    private val trackClip = Path()

    private val islEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    private val eqEase = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    // ---- The song ------------------------------------------------------------------------------------------
    var song: NowPlaying? = null
        private set
    private var compactLine = ""
    private var compactTextW = 0f
    private var openTitle = ""
    private var openTitleW = 0f
    private var openArtist = ""
    private var showSource = false
    private var at = "0:00"
    private var left = ""
    private var leftW = 0f
    private var fill = 0f
    private var artSource: Bitmap? = null
    private var artSmall: Bitmap? = null
    private var artBig: Bitmap? = null

    /** A press on play/pause shows its result at once, until the app confirms it or 2 s pass. */
    private var optimistic: Boolean? = null
    private val dropOptimistic = Runnable {
        optimistic = null
        barsFollowPlaying(true)
        invalidate()
    }

    // ---- Motion ----------------------------------------------------------------------------------------------
    // Shape progress (0 compact, 1 open) and the two layers' opacity, each eased from where it was when the
    // target last changed, so a quick open and close turns round smoothly.
    private var open = false
    private var shape = 0f
    private var compactAlpha = 1f
    private var fullAlpha = 0f
    private val morphFrom = FloatArray(3)
    private var morphStart = -1L

    // The compact width eases to a new song's width with the same morph.
    private var compactW = 0f
    private var compactFrom = 0f
    private var compactTo = 0f
    private var compactStart = -1L

    // The bars' colour eases to a new cover's over 500 ms; paused, they settle to dots over 260 ms.
    private var accent = IslandMath.accent(IslandMath.FALLBACK)
    private var accentFrom = accent
    private var accentTo = accent
    private var accentStart = -1L
    private var barsStart = 0L
    private var barsPlaying = false
    private val restFrom = FloatArray(4)
    private var restStart = -1L

    // island.css's :active: a pressed control dips to 0.94 and comes back, 160 ms each way.
    private var pressed = -1
    private var released = -1
    private var pressStart = -1L
    private var releaseFrom = 1f

    /** For snapshots: the bars drawn as at this many ms into their dance, standing still. */
    internal var frozenBarsAt: Long? = null

    private val barsFrame = Choreographer.FrameCallback { invalidate() }
    private val tick = Runnable {
        refreshTimes()
        invalidate()
    }

    /** Which control has focus inside the island: 0 previous, 1 play/pause, 2 next. */
    var control = 1
        private set

    /** IslandView opens and closes the island around focus (and its peek). */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        isFocusable = true
        // Focus is drawn here, as colour only: no system highlight over it (API 26+ adds one on TVs).
        defaultFocusHighlightEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        compactW = compactWidth()
        compactTo = compactW
    }

    private fun text(face: Typeface, size: Float, color: Int, tracking: Float) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face
            textSize = dp(size)
            this.color = color
            letterSpacing = tracking
        }

    private fun glyph(res: Int, color: Int): Drawable = context.getDrawable(res)!!.mutate().apply { setTint(color) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(openW.roundToInt(), openH.roundToInt())
    }

    // ---- Content -------------------------------------------------------------------------------------------

    /** A new NowPlaying: the song, its art and colour, and whether the bars move. */
    fun show(np: NowPlaying, animate: Boolean) {
        val old = song
        song = np
        if (optimistic == np.playing) {
            optimistic = null
            removeCallbacks(dropOptimistic)
        }
        if (old == null || np.title != old.title || np.artist != old.artist || np.packageName != old.packageName) {
            showSource = np.packageName in SPOTIFY_APPS
            layoutText(np)
            setCompactWidth(compactWidth(), animate && old != null)
        }
        if (np.art !== artSource) setArt(np.art, animate && old != null)
        barsFollowPlaying(animate)
        contentDescription = if (np.artist.isEmpty()) np.title else "${np.title}, ${np.artist}"
        refreshTimes()
        invalidate()
    }

    private fun layoutText(np: NowPlaying) {
        compactLine = TextUtils.ellipsize(np.title, compactText, cTextMax, TextUtils.TruncateAt.END).toString()
        compactTextW = compactText.measureText(compactLine)
        // The title shares its line with the source's mark (16 dp after an 8 dp gap) when it's Spotify.
        val titleRoom = infoW - if (showSource) dp(24f) else 0f
        openTitle = TextUtils.ellipsize(np.title, titleText, titleRoom, TextUtils.TruncateAt.END).toString()
        openTitleW = titleText.measureText(openTitle)
        openArtist = TextUtils.ellipsize(np.artist, artistText, infoW, TextUtils.TruncateAt.END).toString()
    }

    private fun compactWidth() = cLead + cArt + cGap + compactTextW + cGap + cBars + cTrail

    private fun setCompactWidth(w: Float, animate: Boolean) {
        if (!animate) {
            compactW = w
            compactTo = w
            compactStart = -1L
            return
        }
        compactFrom = compactW
        compactTo = w
        compactStart = AnimationUtils.currentAnimationTimeMillis()
    }

    /**
     * The cover at the two sizes it is drawn (22 and 60 dp), each scaled once so drawing is 1:1, and the
     * bars' colour picked from it as the prototype does.
     */
    private fun setArt(art: Bitmap?, animate: Boolean) {
        artSource = art
        artSmall = art?.let { fitted(it, cArt.roundToInt()) }
        artBig = art?.let { fitted(it, dp(60f).roundToInt()) }
        artSmallPaint.shader = artSmall?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artBigPaint.shader = artBig?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val vivid = try {
            art?.let { IslandMath.vivid(sample(it)) }
        } catch (e: RuntimeException) {
            null // a cover whose pixels can't be read (a hardware bitmap): the grey
        }
        val target = IslandMath.accent(vivid ?: IslandMath.FALLBACK)
        if (target == accentTo) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        accentFrom = currentAccent(now)
        accentTo = target
        accentStart = if (animate) now else -1L
        if (!animate) accent = target
    }

    /** A square, centre-cropped copy [size] px a side, halved first when shrinking a lot so it stays smooth. */
    private fun fitted(src: Bitmap, size: Int): Bitmap? = try {
        val side = min(src.width, src.height)
        var b = if (src.width != src.height) {
            Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        } else {
            src
        }
        while (b.width > size * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        if (b.width != size) Bitmap.createScaledBitmap(b, size, size, true) else b
    } catch (e: RuntimeException) {
        null
    }

    /** The cover averaged down to 12 x 12, as the prototype samples it on a canvas. */
    private fun sample(src: Bitmap): IntArray {
        val n = 12
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return IntArray(0)
        val row = IntArray(w)
        val sums = LongArray(n * n * 3)
        val counts = IntArray(n * n)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val cy = y * n / h
            for (x in 0 until w) {
                val c = cy * n + x * n / w
                val p = row[x]
                sums[c * 3] += ((p shr 16) and 0xFF).toLong()
                sums[c * 3 + 1] += ((p shr 8) and 0xFF).toLong()
                sums[c * 3 + 2] += (p and 0xFF).toLong()
                counts[c]++
            }
        }
        return IntArray(n * n) { c ->
            val k = max(1, counts[c])
            (0xFF shl 24) or ((sums[c * 3] / k).toInt() shl 16) or ((sums[c * 3 + 1] / k).toInt() shl 8) or
                (sums[c * 3 + 2] / k).toInt()
        }
    }

    private fun playingShown(): Boolean = optimistic ?: (song?.playing == true)

    /** Starts the bars dancing, or settles them to dots, when playing changes. */
    private fun barsFollowPlaying(animate: Boolean) {
        val playing = playingShown()
        if (playing == barsPlaying) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (playing) {
            barsStart = now
            restStart = -1L
        } else if (animate) {
            for (i in 0..3) restFrom[i] = IslandMath.barScale(eqEase.getInterpolation(IslandMath.barPhase(i, now - barsStart)))
            restStart = now
        }
        barsPlaying = playing
    }

    // ---- Open and closed -----------------------------------------------------------------------------------

    val isOpen: Boolean get() = open

    /** Opens into the player or closes to the pill, with island.css's morph. */
    fun setOpen(value: Boolean, animate: Boolean) {
        val settled = morphStart < 0 && shape == (if (value) 1f else 0f)
        if (value == open && settled) return
        open = value
        if (!animate) {
            morphStart = -1L
            shape = if (value) 1f else 0f
            compactAlpha = if (value) 0f else 1f
            fullAlpha = if (value) 1f else 0f
        } else {
            morphFrom[0] = shape
            morphFrom[1] = compactAlpha
            morphFrom[2] = fullAlpha
            morphStart = AnimationUtils.currentAnimationTimeMillis()
        }
        refreshTimes()
        invalidate()
    }

    // ---- Focus, keys and the pointer ----------------------------------------------------------------------

    /** The pill as drawn right now, so focus search measures from what the viewer sees. */
    override fun getFocusedRect(r: Rect) {
        if (shapeRect.isEmpty) {
            val w = compactW
            r.set(((width - w) / 2f).toInt(), 0, ((width + w) / 2f).toInt(), compactH.toInt())
        } else {
            r.set(shapeRect.left.toInt(), shapeRect.top.toInt(), shapeRect.right.toInt(), shapeRect.bottom.toInt())
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) {
            // Arriving from the right lands on next, from the left on previous, from below on play/pause.
            control = when (direction) {
                FOCUS_LEFT -> 2
                FOCUS_RIGHT -> 0
                else -> 1
            }
        } else if (pressed >= 0) {
            release(false)
        }
        onFocus?.invoke(gainFocus)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (control > 0) {
                control--
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (control < 2) {
                control++
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) press(control)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (pressed >= 0) release(true)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // As Esc in the prototype: Back leaves the island for what's below it.
                focusSearch(FOCUS_DOWN)?.requestFocus(FOCUS_DOWN)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // The remote's pointer: a click on the pill focuses it, a click on a control presses it.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !shapeRect.contains(event.x, event.y)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isFocused) requestFocus()
                val hit = if (open) controlAt(event.x, event.y) else -1
                if (hit >= 0) {
                    control = hit
                    press(hit)
                }
            }
            MotionEvent.ACTION_UP -> if (pressed >= 0) {
                val act = controlAt(event.x, event.y) == pressed
                release(act)
                if (act) performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (pressed >= 0) release(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun controlAt(x: Float, y: Float): Int {
        val r = dp(19f)
        val cy = dp(52f)
        for (i in 0..2) {
            val cx = dp(CTL_X_DP[i])
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) return i
        }
        return -1
    }

    private fun press(i: Int) {
        pressed = i
        released = -1
        pressStart = AnimationUtils.currentAnimationTimeMillis()
        invalidate()
    }

    private fun release(act: Boolean) {
        val i = pressed
        val now = AnimationUtils.currentAnimationTimeMillis()
        releaseFrom = pressScale(i, now)
        pressed = -1
        released = i
        pressStart = now
        invalidate()
        if (!act) return
        when (i) {
            0 -> NowPlayingControls.previous()
            2 -> NowPlayingControls.next()
            1 -> {
                val playing = playingShown()
                if (playing) NowPlayingControls.pause() else NowPlayingControls.play()
                optimistic = !playing
                removeCallbacks(dropOptimistic)
                postDelayed(dropOptimistic, 2000)
                barsFollowPlaying(true)
            }
        }
    }

    // ---- Time ---------------------------------------------------------------------------------------------

    /** Works the times and the progress out for now, and books the next redraw for when they change. */
    private fun refreshTimes() {
        removeCallbacks(tick)
        val np = song ?: return
        val pos = IslandMath.position(np.positionMs, np.positionAtMs, np.speed, np.playing, np.durationMs, SystemClock.elapsedRealtime())
        at = IslandMath.mmss(pos)
        left = if (np.durationMs > 0) IslandMath.left(pos, np.durationMs) else ""
        leftW = timeText.measureText(left)
        fill = IslandMath.progress(pos, np.durationMs)
        if (open && np.playing && np.speed > 0f && isShown && windowVisibility == VISIBLE) {
            postDelayed(tick, IslandMath.msToNextChange(pos, np.durationMs, np.speed))
        }
    }

    /** The island or the home screen came into view, or went: the bars and times run only while seen. */
    fun onShownChanged() {
        refreshTimes()
        if (isShown && windowVisibility == VISIBLE) {
            readDurationScale()
            if (barsPlaying) barsStart = AnimationUtils.currentAnimationTimeMillis()
            invalidate()
        } else {
            Choreographer.getInstance().removeFrameCallback(barsFrame)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onShownChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        removeCallbacks(dropOptimistic)
        Choreographer.getInstance().removeFrameCallback(barsFrame)
    }

    // ---- Drawing ---------------------------------------------------------------------------------------------

    /**
     * The system's animator duration scale (the TV runs at 0.5; 0 turns animations off), read when the
     * island comes into view rather than every frame, so its morph keeps pace with the rest of the screen.
     */
    private var durationScale = 1f

    private fun readDurationScale() {
        durationScale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (e: RuntimeException) {
            1f
        }
    }

    private fun scaled(ms: Long): Long = (ms * durationScale).toLong()

    /** 0 to 1 over [ms] starting [delay] ms after [start], both scaled as the system scales animations. */
    private fun phase(now: Long, start: Long, delay: Long, ms: Long): Float {
        val d = scaled(ms)
        val t = now - start - scaled(delay)
        if (d <= 0L) return if (t >= 0) 1f else 0f
        return (t.toFloat() / d).coerceIn(0f, 1f)
    }

    private fun currentAccent(now: Long): Int {
        if (accentStart < 0) return accentTo
        val t = Tokens.EASE_OUT.getInterpolation(phase(now, accentStart, 0, 500))
        if (t >= 1f) accentStart = -1L
        return mix(accentFrom, accentTo, t)
    }

    override fun onDraw(canvas: Canvas) {
        if (song == null) return
        val now = AnimationUtils.currentAnimationTimeMillis()
        var moving = false

        if (morphStart >= 0) {
            val e = Tokens.EASE_OUT
            val s = islEase.getInterpolation(phase(now, morphStart, 0, 460))
            if (open) {
                // Opening: the pill's content goes at once, the player arrives as the shape settles.
                shape = lerp(morphFrom[0], 1f, s)
                compactAlpha = lerp(morphFrom[1], 0f, e.getInterpolation(phase(now, morphStart, 0, 90)))
                fullAlpha = lerp(morphFrom[2], 1f, e.getInterpolation(phase(now, morphStart, 150, 240)))
            } else {
                // Closing: the player fades out first, then the pill's content comes back.
                shape = lerp(morphFrom[0], 0f, s)
                fullAlpha = lerp(morphFrom[2], 0f, e.getInterpolation(phase(now, morphStart, 0, 110)))
                compactAlpha = lerp(morphFrom[1], 1f, e.getInterpolation(phase(now, morphStart, 170, 200)))
            }
            if (now - morphStart >= scaled(460)) morphStart = -1L else moving = true
        }
        if (compactStart >= 0) {
            compactW = lerp(compactFrom, compactTo, islEase.getInterpolation(phase(now, compactStart, 0, 460)))
            if (now - compactStart >= scaled(460)) compactStart = -1L else moving = true
        }
        accent = currentAccent(now)
        if (accentStart >= 0) moving = true

        val w = lerp(compactW, openW, shape)
        val h = lerp(compactH, openH, shape)
        val r = lerp(compactR, openR, shape)
        val cx = width / 2f
        shapeRect.set(cx - w / 2f, 0f, cx + w / 2f, h)

        canvas.drawRoundRect(shapeRect, r, r, black)
        canvas.save()
        clip.rewind()
        clip.addRoundRect(shapeRect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        if (compactAlpha > 0.004f && drawCompact(canvas, now)) moving = true
        if (fullAlpha > 0.004f && drawOpen(canvas, now)) moving = true
        canvas.restore()
        // Black on black has no shape of its own, so a hairline draws the pill's edge.
        val half = hairline / 2f
        box.set(shapeRect.left + half, half, shapeRect.right - half, h - half)
        canvas.drawRoundRect(box, r - half, r - half, edge)

        val frames = Choreographer.getInstance()
        frames.removeFrameCallback(barsFrame)
        if (moving) {
            postInvalidateOnAnimation()
        } else if (barsDancing()) {
            // Only the bars move: 30 frames a second is plenty for 15 dp, and halves the work.
            frames.postFrameCallbackDelayed(barsFrame, 17)
        }
    }

    private fun barsDancing() =
        frozenBarsAt == null && barsPlaying && compactAlpha > 0.004f && isShown && windowVisibility == VISIBLE

    /** The pill's own row; true while the bars are still settling to dots, so the frames keep coming. */
    private fun drawCompact(canvas: Canvas, now: Long): Boolean {
        val a = compactAlpha
        val x = width / 2f - compactW / 2f
        drawArt(canvas, artSmall, artSmallPaint, x + cLead, dp(6f), cArt, dp(6f), a)
        val tx = x + cLead + cArt + cGap
        compactText.alpha = (255 * a).roundToInt()
        canvas.drawText(compactLine, tx, dp(22f), compactText)
        // Four bars in the cover's colour, on the pill's middle line.
        val bx = tx + compactTextW + cGap
        barPaint.color = accent
        barPaint.alpha = (255 * a).roundToInt()
        var settling = false
        val t = frozenBarsAt ?: (now - barsStart)
        val rest = if (!barsPlaying && restStart >= 0) Tokens.EASE_OUT.getInterpolation(phase(now, restStart, 0, 260)) else 1f
        if (rest < 1f) settling = true else restStart = -1L
        for (i in 0..3) {
            val s = when {
                barsPlaying -> IslandMath.barScale(eqEase.getInterpolation(IslandMath.barPhase(i, t)))
                rest < 1f -> lerp(restFrom[i], IslandMath.BAR_REST, rest)
                else -> IslandMath.BAR_REST
            }
            val bh = dp(15f) * s
            val bl = bx + i * dp(5.5f)
            box.set(bl, dp(17f) - bh / 2f, bl + dp(3f), dp(17f) + bh / 2f)
            canvas.drawRoundRect(box, dp(1.5f), dp(1.5f) * s, barPaint)
        }
        return settling
    }

    /** The player; true while a control's press is still moving. */
    private fun drawOpen(canvas: Canvas, now: Long): Boolean {
        val a = fullAlpha
        val ia = (255 * a).roundToInt()
        val x0 = width / 2f - openW / 2f
        drawArt(canvas, artBig, artBigPaint, x0 + dp(16f), dp(22f), dp(60f), dp(14f), a)
        // The song, with Spotify's mark when it's Spotify, and the artist under it.
        val ix = x0 + dp(88f)
        titleText.alpha = ia
        canvas.drawText(openTitle, ix, dp(31f), titleText)
        if (showSource) {
            val sx = (ix + openTitleW + dp(8f)).roundToInt()
            val sy = dp(17.5f).roundToInt()
            source.setBounds(sx, sy, sx + dp(16f).roundToInt(), sy + dp(16f).roundToInt())
            source.alpha = ia
            source.draw(canvas)
        }
        artistText.alpha = ia
        canvas.drawText(openArtist, ix, dp(50f), artistText)
        // The progress line, and the times under it.
        box.set(ix, dp(65f), ix + infoW, dp(69f))
        trackPaint.alpha = (TRACK ushr 24) * ia / 255
        canvas.drawRoundRect(box, dp(2f), dp(2f), trackPaint)
        if (fill > 0f) {
            canvas.save()
            trackClip.rewind()
            trackClip.addRoundRect(box, dp(2f), dp(2f), Path.Direction.CW)
            canvas.clipPath(trackClip)
            fillPaint.alpha = ia
            canvas.drawRect(box.left, box.top, box.left + box.width() * fill, box.bottom, fillPaint)
            canvas.restore()
        }
        timeText.alpha = ia
        canvas.drawText(at, ix, dp(85f), timeText)
        if (left.isNotEmpty()) canvas.drawText(left, ix + infoW - leftW, dp(85f), timeText)
        // Previous, play/pause, next; the focused one filled with the one accent.
        var moving = false
        val cy = dp(52f)
        for (i in 0..2) {
            val cx = x0 + dp(CTL_X_DP[i])
            val scale = pressScale(i, now)
            if (pressStart >= 0 && (i == pressed || i == released) && now - pressStart < scaled(160)) moving = true
            val focused = isFocused && control == i
            canvas.save()
            canvas.scale(scale, scale, cx, cy)
            if (focused) {
                focusPaint.alpha = ia
                canvas.drawCircle(cx, cy, dp(19f), focusPaint)
            }
            val k = when (i) {
                0 -> 0
                2 -> 3
                else -> if (playingShown()) 2 else 1
            }
            val g = if (focused) glyphOn[k] else glyphOff[k]
            val half = dp(if (i == 1) 12f else 10f)
            g.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
            g.alpha = ia
            g.draw(canvas)
            canvas.restore()
        }
        return moving
    }

    private fun pressScale(i: Int, now: Long): Float {
        if (pressStart < 0) return 1f
        val p = Tokens.EASE_OUT.getInterpolation(phase(now, pressStart, 0, 160))
        return when (i) {
            pressed -> lerp(1f, PRESS, p)
            released -> lerp(releaseFrom, 1f, p)
            else -> 1f
        }
    }

    /** A cover in a rounded square, drawn 1:1 from its own scaled copy; the plain fill until one arrives. */
    private fun drawArt(canvas: Canvas, art: Bitmap?, paint: Paint, x: Float, y: Float, size: Float, radius: Float, a: Float) {
        val p = if (art == null) emptyArt else paint
        p.alpha = (255 * a).roundToInt()
        canvas.save()
        canvas.translate(x, y)
        box.set(0f, 0f, size, size)
        canvas.drawRoundRect(box, radius, radius, p)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun mix(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        val r = ((c0 shr 16 and 0xFF) + ((c1 shr 16 and 0xFF) - (c0 shr 16 and 0xFF)) * t).roundToInt()
        val g = ((c0 shr 8 and 0xFF) + ((c1 shr 8 and 0xFF) - (c0 shr 8 and 0xFF)) * t).roundToInt()
        val b = ((c0 and 0xFF) + ((c1 and 0xFF) - (c0 and 0xFF)) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** The open player's song column: 364 less the paddings, the cover, the controls and the gaps. */
        private const val INFO_W_DP = 130f

        /** The controls' centres from the open island's left (38 dp circles, 4 apart, 12 from the end). */
        private val CTL_X_DP = floatArrayOf(249f, 291f, 333f)
        private const val PRESS = 0.94f

        private const val HAIRLINE = 0x1FFFFFFF // white at 12%
        private const val TRACK = 0x2EFFFFFF // white at 18%
        private const val SPOTIFY = 0xFF2DCB65.toInt() // oklch(74% 0.19 150)
        private const val ACCENT = 0xFF4BB0F8.toInt() // the one accent, oklch(73% 0.14 243)
        private const val ON_ACCENT = 0xFFFFFFFF.toInt()
        private val SPOTIFY_APPS = setOf("com.spotify.tv.android", "com.spotify.music")
    }
}
