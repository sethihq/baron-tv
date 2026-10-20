package com.barontv.home.music

import app.cash.paparazzi.Paparazzi
import com.barontv.home.BARON_TV
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random

/**
 * Music mode at fixed times, fully faded in: the edge light round a red-orange cover (hue 30) and the
 * beat bot's faces. Each frame is posed as the prototype reference frames were (scratch script ref.js):
 * the palette pinned, the bot calm, a face set, then two seconds of frames at 60 fps, and the light at
 * 10 s on the clock, so the two can be compared side by side.
 */
class MusicSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = BARON_TV, showSystemUi = false)

    @Test
    fun blueCover() = shoot(hue = 250.0) { _, _ -> }

    @Test
    fun lightWithSwells() = shoot { e, t ->
        // A kick 300 ms ago from the bottom, a hat 200 ms ago from the top.
        e.light.swell(0.5f, t - 300, 1f)
        e.light.swell(0f, t - 200, 0.35f)
    }

    @Test
    fun plain() = shoot { _, _ -> }

    @Test
    fun smiling() = shoot { e, _ -> e.bot.mood = BeatBot.Mood.SMILE }

    @Test
    fun laughing() = shoot { e, t -> gesture(e.bot, BeatBot.Gesture.GIGGLE, t - 500, t + 600) }

    @Test
    fun hearts() = shoot { e, t -> gesture(e.bot, BeatBot.Gesture.HEARTS, t - 1200, t + 700) }

    @Test
    fun gasp() = shoot { e, t ->
        e.bot.surpriseUntil = t + 400
        e.bot.joyUntil = t + 2000
    }

    @Test
    fun sleepy() = shoot { e, t -> e.bot.lastKick = t - 6000 }

    private fun gesture(bot: BeatBot, g: BeatBot.Gesture, at: Double, until: Double) {
        bot.gesture = g
        bot.gestureAt = at
        bot.gestureUntil = until
    }

    private fun shoot(hue: Double = 30.0, face: (MusicEngine, Double) -> Unit) {
        val engine = posed(hue, T, face)
        val layer = MusicLayer(paparazzi.context, engine)
        layer.render(behindHidden = false)
        paparazzi.snapshot(layer)
    }

    companion object {
        const val T = 10_000.0

        /** The engine as the reference frames have it at [t]: light at full, palette round [hue], the bot calm. */
        internal fun posed(hue: Double, t: Double, face: (MusicEngine, Double) -> Unit): MusicEngine {
            val e = MusicEngine(Random(7))
            e.light.setHue(hue)
            e.light.snapColours()
            e.light.full = 1f
            e.light.fullK = 1f
            e.light.bass = 0.3f
            e.light.high = 0.15f
            calm(e.bot)
            e.bot.showing = true
            e.bot.lastKick = t
            face(e, t)
            var now = t - 2000
            while (now <= t) {
                e.bot.update(now, e.light.bass.toDouble())
                now += 1000.0 / 60
            }
            e.light.frame(t, 0.0)
            e.dim.jump(1f)
            e.glow.jump(1f)
            e.face.jump(1f)
            return e
        }

        /** Still, eyes ahead, nothing due: no blink, glance, dance or mood change during the run. */
        internal fun calm(b: BeatBot) {
            b.x = 0.0; b.xv = 0.0; b.xGoal = 0.0; b.y = 0.0; b.yv = 0.0
            b.tilt = 0.0; b.tiltV = 0.0; b.tiltGoal = 0.0; b.squash = 0.0; b.squashV = 0.0
            b.eyeL = 0.0; b.eyeLV = 0.0; b.eyeR = 0.0; b.eyeRV = 0.0
            b.gx = 0.0; b.gy = 0.0; b.idleX = 0.0; b.idleY = 0.0; b.nextLook = FAR
            b.spin = 0.0; b.spinGoal = 0.0
            b.smile = 0.0; b.joy = 0.0; b.chill = 0.0; b.curious = 0.0; b.wink = 0.0; b.giggle = 0.0
            b.love = 0.0; b.sleepy = 0.0; b.surprise = 0.0
            b.joyUntil = 0.0; b.joyNext = 0.0; b.surpriseUntil = 0.0; b.surpriseNext = FAR
            b.blinkAt = -1e9; b.twice = false; b.nextBlink = FAR
            b.gesture = BeatBot.Gesture.NONE; b.gestureUntil = 0.0; b.gestureNext = FAR
            b.move = BeatBot.Move.NOD; b.moveUntil = FAR; b.mood = BeatBot.Mood.PLAIN; b.moodUntil = FAR
            b.bassSlow = 0.3; b.last = 0.0
        }

        private const val FAR = 1e15
    }
}
