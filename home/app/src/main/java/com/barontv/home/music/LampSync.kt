package com.barontv.home.music

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Philips Wi-Fi colour lamps (WiZ inside) join music mode ("sync it with the music"): each kick flashes
 * them in the edge light's next colour at full brightness, and they sink to a low glow before the next, so
 * the room pulses with the song. When music mode ends, every lamp goes back to exactly what it was doing
 * (its scene, colour or white, and brightness). A lamp that's off stays off.
 *
 * WiZ lamps take JSON over UDP on port 38899 on the home Wi-Fi, no account, once the WiZ app allows it
 * (Settings > Security: allow local communication; the Baron's lamp answered "Invalid params" to every
 * change until then). They're found by a broadcast each time music mode starts. Everything runs on one
 * background thread, and each lamp gets at most one command every [MIN_GAP_MS].
 */
internal class LampSync {
    private var thread: HandlerThread? = null

    @Volatile
    private var worker: Handler? = null

    // ---- Worker thread only ----
    private var socket: DatagramSocket? = null
    private val lamps = mutableListOf<Lamp>()
    private var rgb = 0
    private val sink = Runnable { send(rgb, LOW) }

    private class Lamp(val address: InetAddress, val saved: JSONObject) {
        var lastSent = 0L
    }

    // Main thread: which of the light's three colours the next kick takes.
    private var next = 0

    /** Music mode is starting: find the lamps that are on and remember what they're doing. */
    fun start() {
        if (worker != null) return
        val t = HandlerThread("lamps", Process.THREAD_PRIORITY_DEFAULT)
        t.start()
        val h = Handler(t.looper)
        thread = t
        worker = h
        h.post { discover() }
    }

    /** A kick (main thread, returns at once): every lamp flashes in the light's next colour, then sinks. */
    fun kick(light: EdgeLight) {
        val h = worker ?: return
        val c = light.colour(LOOP[next])
        next = (next + 1) % LOOP.size
        val colour = lampColour(Colour.oklchToRgb(c[0] / 100.0, c[1], c[2]))
        h.post {
            rgb = colour
            h.removeCallbacks(sink)
            send(colour, FULL)
            h.postDelayed(sink, SINK_MS)
        }
    }

    /** Music mode ended: each lamp back to what it was doing, then the thread goes. */
    fun stop() {
        val h = worker ?: return
        val t = thread
        worker = null
        thread = null
        h.removeCallbacksAndMessages(null)
        h.post {
            restore()
            socket?.close()
            socket = null
            lamps.clear()
        }
        t?.quitSafely()
    }

    private fun discover() {
        val s = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 200
            }
        } catch (e: IOException) {
            Log.w(TAG, "No socket for the lamps: $e")
            return
        }
        socket = s
        val ask = ASK.toByteArray()
        val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))
        // Last time's lamps too, by address, in case the router keeps broadcasts from the TV.
        synchronized(known) { known.mapTo(targets) { InetAddress.getByName(it) } }
        for (target in targets) {
            try {
                s.send(DatagramPacket(ask, ask.size, target, PORT))
            } catch (e: IOException) {
                // One target unreachable: the others may still answer.
            }
        }
        val buffer = ByteArray(1024)
        val seen = HashSet<String>()
        val end = SystemClock.uptimeMillis() + LISTEN_MS
        while (SystemClock.uptimeMillis() < end) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                break
            }
            val ip = packet.address.hostAddress ?: continue
            if (!seen.add(ip)) continue
            val result = try {
                JSONObject(String(packet.data, 0, packet.length)).optJSONObject("result")
            } catch (e: JSONException) {
                null
            } ?: continue
            synchronized(known) { known += ip }
            // Off stays off: the music doesn't switch anyone's lamp on.
            if (result.optBoolean("state", false)) lamps += Lamp(packet.address, result)
        }
        if (lamps.isNotEmpty()) Log.i(TAG, "Syncing ${lamps.size} lamp(s) with the music")
    }

    private fun send(colour: Int, dimming: Int) {
        val now = SystemClock.uptimeMillis()
        val text = """{"method":"setPilot","params":{"r":${Colour.red(colour)},"g":${Colour.green(colour)},"b":${Colour.blue(colour)},"dimming":$dimming}}"""
        for (lamp in lamps) {
            if (now - lamp.lastSent < MIN_GAP_MS) continue
            lamp.lastSent = now
            write(lamp.address, text)
        }
    }

    // Back to its scene (with its speed), else its colour, else its white, at its own brightness. Twice,
    // a moment apart: it's UDP, and a lamp left flashing pink would be the one thing worse than no sync.
    private fun restore() {
        for (lamp in lamps) {
            val r = lamp.saved
            val params = JSONObject()
            try {
                when {
                    r.optInt("sceneId", 0) != 0 -> {
                        params.put("sceneId", r.getInt("sceneId"))
                        if (r.has("speed")) params.put("speed", r.getInt("speed"))
                    }
                    r.has("r") -> for (k in COLOUR_KEYS) if (r.has(k)) params.put(k, r.getInt(k))
                    r.has("temp") -> params.put("temp", r.getInt("temp"))
                }
                if (r.has("dimming")) params.put("dimming", r.getInt("dimming"))
            } catch (e: JSONException) {
                continue
            }
            val text = JSONObject().put("method", "setPilot").put("params", params).toString()
            write(lamp.address, text)
            SystemClock.sleep(120)
            write(lamp.address, text)
        }
    }

    private fun write(address: InetAddress, text: String) {
        val s = socket ?: return
        val bytes = text.toByteArray()
        try {
            s.send(DatagramPacket(bytes, bytes.size, address, PORT))
        } catch (e: IOException) {
            // A dropped flash isn't worth a retry: the next kick comes in half a second.
        }
    }

    companion object {
        private const val TAG = "BaronLamps"
        const val PORT = 38899
        private const val ASK = """{"method":"getPilot","params":{}}"""
        private const val LISTEN_MS = 600L
        private const val MIN_GAP_MS = 90L
        private const val SINK_MS = 160L
        private const val FULL = 100
        private const val LOW = 25
        private val LOOP = intArrayOf(0, 1, 2, 1)
        private val COLOUR_KEYS = arrayOf("r", "g", "b", "c", "w")

        /** Lamps seen this run, by address, so a missed broadcast still finds them. */
        private val known = LinkedHashSet<String>()

        /**
         * The lamp takes colour and brightness apart: the colour as the full-strength mix (its brightest
         * channel at 255), the brightness from `dimming`. So the light's colour is scaled up to full.
         */
        fun lampColour(rgb: Int): Int {
            val r = Colour.red(rgb)
            val g = Colour.green(rgb)
            val b = Colour.blue(rgb)
            val top = max(1, max(r, max(g, b)))
            fun up(v: Int) = (v * 255.0 / top).roundToInt().coerceIn(0, 255)
            return Colour.rgb(up(r), up(g), up(b))
        }
    }
}
