package com.barontv.home.side

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import com.barontv.home.R

/** A network as the Wi-Fi view lists it. */
internal data class Net(val ssid: String, val rssi: Int, val locked: Boolean)

/** Everything the Wi-Fi view shows at one moment. */
internal data class WifiUi(
    val enabled: Boolean,
    /** The network in use, or the one being joined. */
    val current: Net? = null,
    val joining: Boolean = false,
    val others: List<Net> = emptyList(),
    /** No scan has answered yet since the view opened. */
    val scanning: Boolean = false,
)

/** Wi-Fi's rules, free of Android types so they can be tested on the JVM. */
internal object WifiMath {
    /** WifiManager.calculateSignalLevel(rssi, 4) as Android 9 works it out: 0 to 3. */
    fun level(rssi: Int): Int = when {
        rssi <= -100 -> 0
        rssi >= -55 -> 3
        else -> ((rssi + 100) * 3f / 45f).toInt()
    }

    /** The strength as Phosphor draws it: the fan's arcs it reaches. */
    fun glyph(rssi: Int): Int = when (level(rssi)) {
        3 -> R.drawable.ph_wifi_high
        2 -> R.drawable.ph_wifi_medium
        else -> R.drawable.ph_wifi_low
    }

    /** Needs a password (or more): WEP, WPA/WPA2/WPA3 personal or enterprise. */
    fun locked(capabilities: String?): Boolean =
        capabilities != null && LOCKS.any { capabilities.contains(it) }

    private val LOCKS = arrayOf("WEP", "PSK", "EAP", "SAE")

    /** One row per name, the strongest first (by name when equal); hidden networks and [exclude] left out. */
    fun nearby(scan: List<Net>, exclude: String?): List<Net> {
        val best = LinkedHashMap<String, Net>()
        for (n in scan) {
            if (n.ssid.isEmpty() || n.ssid == exclude) continue
            val had = best[n.ssid]
            if (had == null || n.rssi > had.rssi) best[n.ssid] = n
        }
        return best.values.sortedWith(compareByDescending<Net> { it.rssi }.thenBy { it.ssid })
    }

    /** An SSID as WifiInfo or NetworkInfo give it, unquoted; null when Android hides it. */
    fun clean(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (s.isEmpty() || s == "0x" || s.contains("unknown ssid", ignoreCase = true)) return null
        return s
    }

    fun quoted(ssid: String) = "\"$ssid\""
}

/**
 * WifiManager for the Wi-Fi view, everything on the dock's worker thread: the state, the network in use,
 * the scan (one started when the view opens; its results broadcast follows), joining a saved network.
 * Its receiver is registered only while the view is open.
 *
 * On Android 9, scan results, saved networks and the network's name are only given to an app with the
 * location permission (granted at install) while the TV's location setting is on.
 *
 * Deprecation is allowed here: this runs on Android 9 only, and these WifiManager and NetworkInfo calls
 * are the ones it has (their replacements came in later versions).
 */
@Suppress("DEPRECATION")
internal class WifiLink(
    private val context: Context,
    private val worker: SideWorker,
    private val onUi: (WifiUi) -> Unit,
    private val onMessage: (String) -> Unit,
    private val openSettings: () -> Unit,
) {
    // Fetched on first use, so making the view costs nothing until it opens on the TV.
    private val wifi by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    // Set on the main thread, read on the worker so nothing is posted once the view has closed.
    @Volatile
    private var listening = false

    // ---- The worker's own state ----
    private var scanning = false
    private var joining: Net? = null
    private var joiningId = -1
    private val scanTimeout = Runnable {
        scanning = false
        refresh()
    }
    private val giveUp = Runnable {
        val net = joining ?: return@Runnable
        joining = null
        refresh()
        val text = context.getString(R.string.side_could_not_join, net.ssid)
        worker.toMain { onMessage(text) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    scanning = false
                    worker.handler().removeCallbacks(scanTimeout)
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 0) == WifiManager.WIFI_STATE_ENABLED) scan()
                }
            }
            checkJoined()
            refresh()
        }
    }

    /** The view opened: follow Wi-Fi and look for networks. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // system broadcasts only, on API 28
    fun start() {
        if (listening || wifi == null) {
            if (wifi == null) onUi(WifiUi(enabled = false))
            return
        }
        listening = true
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter, null, worker.handler())
        worker.post {
            scan()
            refresh()
        }
    }

    /** The view closed: stop following, forget a join in progress (Android carries on with it). */
    fun stop() {
        if (!listening) return
        listening = false
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Never registered: nothing to undo.
        }
        worker.post {
            worker.handler().removeCallbacks(scanTimeout)
            worker.handler().removeCallbacks(giveUp)
            joining = null
            scanning = false
        }
    }

    fun setEnabled(on: Boolean) {
        val w = wifi ?: return
        worker.post {
            w.isWifiEnabled = on
            refresh()
        }
    }

    /**
     * A row was chosen: a saved network is joined here ("Connecting…", then "Connected"); any other needs
     * its password, so Android's Wi-Fi settings open for it.
     */
    fun choose(net: Net) {
        val w = wifi ?: return
        worker.post {
            val saved = try {
                w.configuredNetworks?.firstOrNull { it.SSID == WifiMath.quoted(net.ssid) }
            } catch (e: SecurityException) {
                null
            }
            if (saved == null) {
                worker.toMain(openSettings)
                return@post
            }
            joining = net
            joiningId = saved.networkId
            refresh()
            w.enableNetwork(saved.networkId, true)
            val h = worker.handler()
            h.removeCallbacks(giveUp)
            h.postDelayed(giveUp, JOIN_TIMEOUT_MS)
        }
    }

    private fun scan() {
        val w = wifi ?: return
        scanning = try {
            w.startScan()
        } catch (e: SecurityException) {
            false
        }
        val h = worker.handler()
        h.removeCallbacks(scanTimeout)
        if (scanning) h.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    /** Joined: the network in use is the one asked for, and it's up. */
    private fun checkJoined() {
        val net = joining ?: return
        val w = wifi ?: return
        val info = w.connectionInfo ?: return
        if (info.networkId == joiningId && info.supplicantState == SupplicantState.COMPLETED && wifiConnected()) {
            joining = null
            worker.handler().removeCallbacks(giveUp)
            val text = context.getString(R.string.side_connected_to, net.ssid)
            worker.toMain { onMessage(text) }
        }
    }

    private fun wifiInfo(): android.net.NetworkInfo? = try {
        connectivity?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
    } catch (e: RuntimeException) {
        null
    }

    private fun wifiConnected(): Boolean = wifiInfo()?.isConnected == true

    private fun refresh() {
        val w = wifi ?: return
        if (!listening) return
        val state = w.wifiState
        val enabled = state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
        if (!enabled) {
            post(WifiUi(enabled = false))
            return
        }
        val info = w.connectionInfo
        val connected = info != null && info.networkId != -1 && info.supplicantState == SupplicantState.COMPLETED
        val current = joining ?: if (connected) Net(nameOf(info?.ssid), info?.rssi ?: -100, false) else null
        val scan = try {
            w.scanResults.orEmpty().map {
                Net(it.SSID.orEmpty(), it.level, WifiMath.locked(it.capabilities))
            }
        } catch (e: SecurityException) {
            emptyList()
        }
        val others = WifiMath.nearby(scan, current?.ssid)
        // Still turning on counts as looking: its scan starts once it's on.
        val looking = scanning || state == WifiManager.WIFI_STATE_ENABLING
        post(WifiUi(enabled = true, current = current, joining = joining != null, others = others, scanning = looking))
    }

    /**
     * The network in use: WifiInfo's name, or (with the location setting off, when Android 9 hides it
     * there) NetworkInfo's, which still carries it on this version; "Wi-Fi" if neither says.
     */
    private fun nameOf(ssid: String?): String {
        WifiMath.clean(ssid)?.let { return it }
        WifiMath.clean(wifiInfo()?.extraInfo)?.let { return it }
        return context.getString(R.string.side_wifi)
    }

    private fun post(ui: WifiUi) {
        worker.toMain { onUi(ui) }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val JOIN_TIMEOUT_MS = 20_000L
    }
}
