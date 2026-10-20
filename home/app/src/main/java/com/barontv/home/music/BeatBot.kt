package com.barontv.home.music

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The beat bot, ported from the prototype's final version: round eyes and a small smile, flat white, no
 * glow, dancing to the song in the middle of music mode. The reference is FluxGarage RoboEyes (GPL-3.0),
 * so none of its code is used; this follows the prototype's own drawing.
 *
 * It dances on the kicks, a new dance every 4 to 6.5 s: a nod, a sway, hops, side steps, eyes bouncing in
 * turn, looking left and right, eyes circling. Its mood changes every 7 to 12 s: plain, smiling, chilled
 * (a contented squint, smaller moves) or curious (one eye bigger, head on one side, a sideways smile).
 * Every 3 to 6 s it does something small on the beat: a wink, a double blink, a laugh, a glance about, or
 * heart eyes (the biggest hits bring hearts or a beaming squint). It hears the song: a drop makes it gasp
 * then beam, 3.5 s without a beat makes it sleepy, and the beat coming back wakes it with a start. It
 * blinks every 1.6 to 4.8 s, a quarter of them double. Springs carry every move, so it lands softly.
 *
 * Times are milliseconds on the music clock. [update] runs once a frame and fills [pose], which BotView
 * paints; nothing here allocates. Plain Kotlin, so its timing is tested on the JVM.
 */
internal class BeatBot(private val random: Random) {

    enum class Move { NOD, SWAY, HOP, STEP, BOUNCE, LOOK, CIRCLE }

    enum class Mood { PLAIN, SMILE, CHILL, CURIOUS }

    enum class Gesture(val ms: Double) { NONE(0.0), WINK(750.0), BLINK(500.0), GIGGLE(1100.0), GLANCE(1300.0), HEARTS(1900.0) }

    /** Only while it shows (music mode) does it dance; kicks still count, so it knows how long it's been. */
    var showing = false

    var last = 0.0
    var beats = 0
    var move = Move.NOD
    var moveUntil = 0.0
    var mood = Mood.PLAIN
    var moodUntil = 0.0
    var gesture = Gesture.NONE
    var gestureAt = 0.0
    var gestureUntil = 0.0
    var gestureNext = 0.0

    // The body: springs for position, tilt and squash.
    var x = 0.0
    var xv = 0.0
    var xGoal = 0.0
    var y = 0.0
    var yv = 0.0
    var tilt = 0.0
    var tiltV = 0.0
    var tiltGoal = 0.0
    var squash = 0.0
    var squashV = 0.0

    // Each eye bounces on its own in the bounce dance.
    var eyeL = 0.0
    var eyeLV = 0.0
    var eyeR = 0.0
    var eyeRV = 0.0

    // Where the eyes look.
    var gx = 0.0
    var gy = 0.0
    var idleX = 0.0
    var idleY = 0.0
    var nextLook = 0.0
    var lookSide = 1
    var spin = 0.0
    var spinGoal = 0.0

    // Expressions, eased between 0 and 1.
    var smile = 0.0
    var joy = 0.0
    var chill = 0.0
    var curious = 0.0
    var wink = 0.0
    var giggle = 0.0
    var love = 0.0
    var sleepy = 0.0
    var surprise = 0.0
    var joyUntil = 0.0
    var joyNext = 0.0
    var surpriseUntil = 0.0
    var surpriseNext = 0.0
    var blinkAt = -1e9
    var twice = false
    var nextBlink = 0.0
    var lastKick = 0.0
    var bassSlow = 0.3

    /** The face as worked out by the last [update], in the prototype's 360 x 240 canvas units. */
    val pose = BotPose()

    fun blink(now: Double, double: Boolean = false) {
        blinkAt = now
        twice = double
        nextBlink = now + 1600 + random.nextDouble() * 3200
    }

    /** A gasp: big eyes and an "o", then a beam. */
    fun surprise(now: Double) {
        surpriseUntil = now + 800
        joyUntil = now + 2400
        yv -= 260
    }

    fun gesture(now: Double, name: Gesture) {
        gesture = name
        gestureAt = now
        gestureUntil = now + name.ms
        gestureNext = now + 3000 + random.nextDouble() * 3000
        if (name == Gesture.BLINK) blink(now, true)
    }

    /** A kick: the dance moves on it. [amp] is how hard it hit, up to 1. */
    fun kick(now: Double, amp: Double) {
        val woke = now - lastKick > 3500 && sleepy > 0.3
        lastKick = now
        if (!showing) return
        // The beat coming back after a quiet stretch: awake with a start.
        if (woke) surprise(now)
        beats += 1
        // A new dance every few seconds and a new mood every few bars, always on a beat, with a blink.
        if (now > moveUntil) {
            move = pickNew(MOVES, move)
            moveUntil = now + 4000 + random.nextDouble() * 2500
            blink(now)
        }
        if (now > moodUntil) {
            mood = pickNew(MOODS, mood)
            moodUntil = now + 7000 + random.nextDouble() * 5000
        }
        val side = if (beats % 2 != 0) 1 else -1
        val a = amp * (if (mood == Mood.CHILL) 0.6 else 1.0)
        xGoal = 0.0
        tiltGoal = 0.0
        when (move) {
            Move.NOD -> {
                // Down on the beat, as a head nods.
                yv += 240 * a
                squashV -= 4 * a
            }
            Move.SWAY -> {
                tiltGoal = side * (7 + 5 * a)
                xGoal = side * 8.0
                squashV -= 2 * a
            }
            Move.HOP -> {
                yv -= 480 * a
                squashV += 4 * a
            }
            Move.STEP -> {
                xGoal = side * 26.0
                tiltGoal = side * 3.0
                yv += 120 * a
                squashV -= 2 * a
            }
            Move.BOUNCE -> {
                if (side > 0) eyeLV -= 380 * a else eyeRV -= 380 * a
                squashV -= 1.5 * a
            }
            Move.LOOK -> {
                lookSide = side
                yv += 100 * a
            }
            Move.CIRCLE -> {
                spinGoal += Math.PI / 2
                yv += 80 * a
            }
        }
        // Something small every few seconds; the biggest hits sometimes bring hearts or a delighted squint.
        if (now > gestureUntil) {
            if (amp > 0.95 && now > joyNext) {
                joyNext = now + 5000
                if (random.nextDouble() < 0.5) gesture(now, Gesture.HEARTS) else joyUntil = now + 1300
            } else if (now > gestureNext) {
                gesture(now, pickNew(GESTURES, gesture))
            }
        }
    }

    /** The off-beats: a small tick, and now and then the eyes flick to the side and back. */
    fun hat() {
        if (!showing) return
        squashV -= 1
        if (random.nextDouble() < 0.3) gx += (if (random.nextDouble() < 0.5) -1 else 1) * 7
    }

    /** One frame at [now]: the song's [bass] (0 to 1) for drops, then springs, expressions and [pose]. */
    fun update(now: Double, bass: Double) {
        val dt = min(0.05, (now - (if (last == 0.0) now else last)) / 1000.0)
        last = now

        // What the song is doing: a sudden jump in the bass (a drop), or a long quiet.
        bassSlow += (bass - bassSlow) * min(1.0, dt * 0.8)
        if (bass - bassSlow > 0.32 && now > surpriseNext) {
            surprise(now)
            surpriseNext = now + 9000
        }
        val quiet = now - lastKick > 3500
        val g = if (now < gestureUntil) gesture else Gesture.NONE

        // Where the eyes look: darting about in a glance, the look dance, circling, or idle glances.
        val tx: Double
        val ty: Double
        if (g == Gesture.GLANCE) {
            tx = (if (floor((now - gestureAt) / 320).toLong() % 2 != 0L) 1 else -1) * 24.0
            ty = -3.0
        } else if (move == Move.LOOK && !quiet) {
            tx = lookSide * 22.0
            ty = -2.0
        } else if (move == Move.CIRCLE && !quiet) {
            spin = ease(spin, spinGoal, 7.0, dt)
            tx = cos(spin) * 18
            ty = sin(spin) * 9
        } else {
            if (now > nextLook) {
                idleX = (random.nextDouble() * 2 - 1) * 14
                idleY = (random.nextDouble() * 2 - 1) * 6
                nextLook = now + 1500 + random.nextDouble() * 2500
            }
            tx = idleX
            ty = idleY
        }
        gx = ease(gx, tx, 10.0, dt)
        gy = ease(gy, ty, 10.0, dt)

        // Blinks every couple of seconds, sometimes twice.
        if (now > nextBlink) blink(now, random.nextDouble() < 0.25)
        val since = (now - blinkAt) / 1000
        val blink = max(blinkShape(since), if (twice) blinkShape(since - 0.24) else 0.0)

        // Springs.
        xv = springV(x, xv, xGoal, 90.0, 12.0, dt); x += xv * dt
        yv = springV(y, yv, 0.0, 170.0, 12.0, dt); y += yv * dt
        tiltV = springV(tilt, tiltV, tiltGoal, 70.0, 10.0, dt); tilt += tiltV * dt
        squashV = springV(squash, squashV, 0.0, 220.0, 13.0, dt); squash += squashV * dt
        eyeLV = springV(eyeL, eyeLV, 0.0, 200.0, 12.0, dt); eyeL += eyeLV * dt
        eyeRV = springV(eyeR, eyeRV, 0.0, 200.0, 12.0, dt); eyeR += eyeRV * dt

        // Expressions.
        val m = if (quiet) null else mood
        surprise = ease(surprise, if (now < surpriseUntil) 1.0 else 0.0, 12.0, dt)
        joy = ease(joy, if (now < joyUntil && now > surpriseUntil) 1.0 else 0.0, 9.0, dt)
        sleepy = ease(sleepy, if (quiet) 1.0 else 0.0, if (quiet) 1.2 else 6.0, dt)
        smile = ease(smile, if (m == Mood.SMILE) 1.0 else 0.0, 4.0, dt)
        chill = ease(chill, if (m == Mood.CHILL) 1.0 else 0.0, 4.0, dt)
        curious = ease(curious, if (m == Mood.CURIOUS) 1.0 else 0.0, 4.0, dt)
        wink = ease(wink, if (g == Gesture.WINK) 1.0 else 0.0, 14.0, dt)
        giggle = ease(giggle, if (g == Gesture.GIGGLE) 1.0 else 0.0, 10.0, dt)
        love = ease(love, if (g == Gesture.HEARTS) 1.0 else 0.0, 7.0, dt)

        shape(now, blink)
    }

    // The face from the state, as the prototype's drawBot draws it.
    private fun shape(now: Double, blink: Double) {
        val p = pose
        val shake = sin(now / 38) * 3 * giggle
        p.cx = (W / 2 + x).toFloat()
        p.cy = (H / 2 - 8 + y + shake + sleepy * 6).toFloat()
        p.tilt = (tilt + curious * 7 + sleepy * 5).toFloat()

        val sq = squash.coerceIn(-0.4, 0.4)
        val big = 1 + surprise * 0.22
        val joyful = max(joy, giggle) * (1 - surprise)
        // Shorter eyes: a contented squint when chilled, lifted by a smile, half shut and breathing when sleepy.
        val short = (1 - 0.25 * chill) * (1 - 0.12 * smile) * (1 - sleepy * (0.55 + 0.04 * sin(now / 650)))
        // Heart eyes: the eyes shrink away, then hearts pop in and throb.
        val eyeScale = (1 - love * 2).coerceIn(0.0, 1.0)
        val heartScale = if (love > 0.5) popOut((love - 0.5) * 2) else 0.0
        for (i in 0 until 2) {
            val left = i == 0
            val side = if (left) -1 else 1
            val e = p.eyes[i]
            e.x = (side * 52 + gx).toFloat()
            e.y = (gy + (if (left) eyeL else eyeR) - 3 * smile + 5 * sleepy).toFloat()
            if (heartScale > 0) {
                e.kind = EyePose.HEART
                e.size = (64 * big * heartScale * (1 - sq * 0.4)).toFloat()
                continue
            }
            if (eyeScale < 0.03) {
                e.kind = EyePose.NONE
                continue
            }
            val size = big * eyeScale * (if (left) 1 - 0.1 * curious else 1 + 0.16 * curious)
            val w = 66 * size * (1 - sq * 0.3)
            val h = max(4.0, 76 * size * short * (1 + sq * 0.6) * (1 - blink * 0.95))
            // Happy: the eye closes to a line, and the line bends up into an arch like ^, thick with round
            // ends (a wink is one of them). No fading between the two, so it never goes grey on the way.
            val arch = if (left) joyful else max(joyful, wink)
            if (arch < 0.5) {
                val t = arch * 2
                val hh = h * (1 - t) + 10 * t
                e.kind = EyePose.ROUND
                e.w = w.toFloat()
                e.h = hh.toFloat()
                e.corner = min(24 + 10 * surprise, min(hh / 2, w / 2)).toFloat()
            } else {
                val k = (arch - 0.5) * 2
                e.kind = EyePose.ARCH
                e.reach = (w * (0.42 - 0.06 * k)).toFloat()
                e.lift = k.toFloat()
                e.stroke = ((10 + 5 * k) * size).toFloat()
            }
        }

        // The smile: wider when happy, open in a laugh, an "o" when it gasps, a short flat line when
        // sleepy, pulled to one side when curious or winking.
        val grin = max(max(smile, joyful), max(love, chill * 0.5))
        p.mouthX = (gx * 0.5 + 6 * wink - 6 * curious).toFloat()
        p.mouthY = (50 + gy * 0.5).toFloat()
        p.mouthW = ((11 + 6 * grin) * (1 - 0.4 * sleepy) * (1 - 0.3 * curious)).toFloat()
        p.mouthD = ((7 + 6 * grin + 10 * giggle) * (1 - sleepy) * (1 - 0.6 * curious)).toFloat()
        p.mouthLine = if (surprise < 0.98) (1 - surprise).toFloat() else 0f
        p.mouthFill = if (surprise < 0.98 && giggle > 0.02) (giggle * (1 - surprise)).toFloat() else 0f
        p.oh = if (surprise > 0.02) surprise.toFloat() else 0f
    }

    private fun <T> pickNew(list: Array<T>, current: T): T {
        var n = 0
        for (item in list) if (item != current) n++
        var k = random.nextInt(n)
        for (item in list) {
            if (item == current) continue
            if (k == 0) return item
            k--
        }
        return list[0]
    }

    companion object {
        /** The bot's canvas, 360 x 240 dp, centred on the screen. */
        const val W = 360.0
        const val H = 240.0

        private val MOVES = Move.values()

        // Smiling is listed twice, so it comes up more often.
        private val MOODS = arrayOf(Mood.PLAIN, Mood.SMILE, Mood.CHILL, Mood.CURIOUS, Mood.SMILE)
        private val GESTURES = arrayOf(Gesture.WINK, Gesture.BLINK, Gesture.GIGGLE, Gesture.GLANCE, Gesture.HEARTS)

        /** A blink's shape over time in seconds: shut in 70 ms, open again over 120 ms. */
        fun blinkShape(t: Double): Double = when {
            t < 0 -> 0.0
            t < 0.07 -> t / 0.07
            t < 0.19 -> 1 - (t - 0.07) / 0.12
            else -> 0.0
        }

        /** A damped spring's new velocity towards [goal]; the position then moves by it. */
        fun springV(x: Double, v: Double, goal: Double, k: Double, c: Double, dt: Double): Double {
            val a = -k * (x - goal) - c * v
            return v + a * dt
        }

        fun ease(x: Double, goal: Double, rate: Double, dt: Double): Double = x + (goal - x) * min(1.0, dt * rate)

        /** From 0 to 1 with a little overshoot, for the hearts popping in. */
        fun popOut(t: Double): Double {
            val u = t - 1
            return 1 + 2.7 * u * u * u + 1.7 * u * u
        }
    }
}

/** The face to paint, in the bot canvas's units: the body's centre and tilt, both eyes and the mouth. */
internal class BotPose {
    var cx = 0f
    var cy = 0f
    var tilt = 0f
    val eyes = arrayOf(EyePose(), EyePose())
    var mouthX = 0f
    var mouthY = 0f
    var mouthW = 0f
    var mouthD = 0f

    /** The smile's stroke and its laughing fill, as opacities (0 for none). */
    var mouthLine = 0f
    var mouthFill = 0f

    /** The gasp's "o", as an opacity. */
    var oh = 0f
}

/** One eye: a rounded rectangle, an arch (^), a heart, or nothing while the hearts take over. */
internal class EyePose {
    var kind = ROUND
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var corner = 0f
    var reach = 0f
    var lift = 0f
    var stroke = 0f
    var size = 0f

    companion object {
        const val NONE = 0
        const val ROUND = 1
        const val ARCH = 2
        const val HEART = 3
    }
}
