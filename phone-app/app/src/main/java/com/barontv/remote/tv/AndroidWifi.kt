package com.barontv.remote.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.Inet4Address
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Watches the phone's Wi-Fi with a [ConnectivityManager.NetworkCallback]: reports the network the phone is
 * on (with its IPv4 address and prefix, for the scan), again when that changes, and null without Wi-Fi.
 */
internal class AndroidWifiWatcher(context: Context, private val log: (String) -> Unit) : WifiWatcher {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** The Wi-Fi network in use, so the TV's traffic can be sent over it (see [WifiRoute]). */
    @Volatile
    var network: Network? = null
        private set

    override fun links(): Flow<WifiLink?> = callbackFlow {
        val cm = connectivity
        if (cm == null) {
            trySend(null)
            awaitClose()
            return@callbackFlow
        }
        // Every Wi-Fi network the phone is on, newest last. Two at once is rare (while switching, say);
        // the newest is the one used.
        val networks = LinkedHashMap<Network, LinkProperties?>()
        var heard = false

        fun publish() {
            val newest = networks.entries.lastOrNull()
            network = newest?.key
            trySend(newest?.let { linkOf(it.key, it.value) })
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) = synchronized(networks) {
                heard = true
                networks[net] = cm.getLinkProperties(net)
                publish()
            }

            override fun onLinkPropertiesChanged(net: Network, properties: LinkProperties) = synchronized(networks) {
                heard = true
                networks[net] = properties
                publish()
            }

            override fun onLost(net: Network) = synchronized(networks) {
                heard = true
                networks.remove(net)
                publish()
            }
        }
        val wifiOnly = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The TV is on the home network whether or not that network reaches the internet.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(wifiOnly, callback)
        } catch (e: RuntimeException) {
            // Registering can throw on a few phones. Then what's there now is all we'll know.
            log("Can't watch the Wi-Fi: $e")
        }
        // The callback only reports networks that exist, so with no Wi-Fi it would never say a word.
        // Start from what's there now, unless it has already spoken.
        synchronized(networks) {
            if (!heard) {
                currentWifi(cm)?.let { networks[it] = cm.getLinkProperties(it) }
                publish()
            }
        }
        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                // It never got registered.
            }
            network = null
        }
    }.conflate().distinctUntilChanged()

    private fun linkOf(net: Network, properties: LinkProperties?): WifiLink {
        val v4 = properties?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        return WifiLink(net.toString(), v4?.address?.hostAddress, v4?.prefixLength ?: 0)
    }

    /** The Wi-Fi network the phone is on right now, if any (not a VPN running over it). */
    @Suppress("DEPRECATION") // allNetworks: the one way to list networks without waiting for a callback.
    private fun currentWifi(cm: ConnectivityManager): Network? {
        fun isWifi(net: Network): Boolean {
            val capabilities = cm.getNetworkCapabilities(net) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.let { if (isWifi(it)) return it }
        return cm.allNetworks.firstOrNull { isWifi(it) }
    }
}

/**
 * Sockets and HTTP connections that go out over the phone's Wi-Fi. Without this, a phone whose default
 * network has moved to mobile data (a Wi-Fi without internet, or a weak signal) would send the TV's
 * traffic there, where the TV isn't. When binding is refused, as under a VPN that can't be bypassed, the
 * default route is used, as it would be by any other app.
 */
internal class WifiRoute(private val wifi: AndroidWifiWatcher) {

    fun newSocket(): Socket {
        val net = wifi.network ?: return Socket()
        return try {
            net.socketFactory.createSocket()
        } catch (e: IOException) {
            Socket()
        }
    }

    fun open(url: URL): URLConnection {
        val net = wifi.network
        if (net == null || !canBind(net)) return url.openConnection(Proxy.NO_PROXY)
        return net.openConnection(url, Proxy.NO_PROXY)
    }

    /** Creating a socket from the network's factory binds it, so a refusal shows up here. */
    private fun canBind(net: Network): Boolean = try {
        net.socketFactory.createSocket().close()
        true
    } catch (e: IOException) {
        false
    }
}
