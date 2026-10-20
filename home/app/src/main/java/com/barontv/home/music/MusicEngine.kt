package com.barontv.home.music

import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Music mode's moving parts on one clock: the dim, the edge light, the beat bot, the beat itself (heard,
 * or a steady tempo when nothing can be heard) and the fades between them, as the prototype times them
 * (edge.css): the black fades up over 1.8 s and back over 1.4 s, the light over 0.9 s, the bot over
 * 1.2 s after half a second, both out over 0.9 s.
 *
 * Times are milliseconds on the music clock (Choreographer's, on the TV); tests and snapshots drive it
 * by hand. No Android here.
 */
internal class MusicEngine(random: Random) : BeatListener {
    val light = EdgeLight()
    val bot = BeatBot(random)

    /** The black over the home screen, the light and the bot, each an opacity. */
    val dim = Fade()
    val glow = Fade()
    val face = Fade()

    var on = false
        private set

    /**
     * Leaving, the light goes under the dim (it went behind the UI in the prototype), so it fades out
     * with the black instead of over the home screen coming back.
     */
    var lightUnderDim = false
        private set

    /** Nothing moves (the TV's animations are off): a still light and still eyes, no fades. */
    var still = false

    /** True while the Visualizer feeds the beat; otherwise a steady [TEMPO_BPM] does. */
    var hearing = false

    private var next = 0.0
    private var count = 0
    private var lastFrame = 0.0

    fun enter(now: Double) {
        if (on) return
        on = true
        lightUnderDim = false
        light.full = 1f
        if (glow.value == 0f) {
            // Starting from dark: the light grows from nothing in the cover's colours, as it did in the
            // prototype, where it had been running unseen while the song played.
            light.fullK = 0f
            light.clearSwells()
            light.snapColours()
        }
        // Awake: in the prototype the bot had been hearing the beat all along.
        bot.lastKick = now
        bot.showing = true
        next = now
        if (still) {
            light.fullK = 1f
            dim.jump(1f)
            glow.jump(1f)
            face.jump(1f)
        } else {
            dim.go(1f, now, 1800.0)
            glow.go(1f, now, 900.0)
            face.go(1f, now, 1200.0, 500.0)
        }
        lastFrame = now
    }

    fun exit(now: Double) {
        if (!on) return
        on = false
        lightUnderDim = true
        light.full = 0f
        bot.showing = false
        if (still) {
            off()
        } else {
            dim.go(0f, now, 1400.0)
            glow.go(0f, now, 900.0)
            face.go(0f, now, 900.0)
        }
    }

    /** Everything off at once: the home screen left the front. */
    fun off() {
        on = false
        bot.showing = false
        light.full = 0f
        dim.jump(0f)
        glow.jump(0f)
        face.jump(0f)
    }

    /** Nothing showing and nothing moving: the frame loop can stop. */
    val settled: Boolean get() = !on && dim.value == 0f && glow.value == 0f && face.value == 0f

    /** One frame at [now]. */
    fun frame(now: Double) {
        val dt = ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.1)
        lastFrame = now
        if (on && !hearing && !still) tempo(now)
        light.frame(now, dt)
        // The bot freezes as it fades out, as it did in the prototype.
        if (bot.showing) bot.update(now, light.bass.toDouble())
        dim.step(now)
        glow.step(now)
        face.step(now)
    }

    // Without sound to hear: a steady tempo, the first and third beats from the bottom, the second and
    // fourth small ones from the top. The beats keep to their grid (the prototype counted each from the
    // frame it landed on, which slowed 112 to about 109 at 60 fps).
    private fun tempo(now: Double) {
        if (now >= next) {
            next += 60000.0 / TEMPO_BPM
            if (next <= now) next = now + 60000.0 / TEMPO_BPM
            if (count % 2 == 0) kick(now, 0.9) else hat(now)
            count = (count + 1) % 4
        }
        light.bass = (0.3 + 0.1 * sin(now / 900)).toFloat()
        light.high = 0.15f
    }

    /** Told of each kick while music mode shows (the lamps flash on it). */
    var onKick: (() -> Unit)? = null

    override fun kick(now: Double, amp: Double) {
        bot.kick(now, amp)
        light.swell(0.5f, now, amp.toFloat())
        if (on) onKick?.invoke()
    }

    override fun hat(now: Double) {
        bot.hat()
        light.swell(0f, now, 0.35f)
    }

    override fun levels(bass: Double, high: Double, dt: Double, snap: Boolean) {
        if (snap) {
            // The first hearing: straight to the song's level, so its bass isn't taken for a drop.
            light.bass = bass.toFloat()
            light.high = high.toFloat()
            bot.bassSlow = bass
            return
        }
        light.bass += ((bass - light.bass) * min(1.0, EdgeLight.perFrame(0.2, dt))).toFloat()
        light.high += ((high - light.high) * min(1.0, EdgeLight.perFrame(0.25, dt))).toFloat()
    }

    companion object {
        /** The steady beat when the TV's sound can't be heard: a felt tempo for most pop. */
        const val TEMPO_BPM = 112.0
    }
}
