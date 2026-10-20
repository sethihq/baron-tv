package com.barontv.remote.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/** How long one service may take to resolve before the next one is tried. */
private const val RESOLVE_LIMIT_MS = 3_000L

/**
 * Finds the TV over mDNS with [NsdManager]: discovers `_cloudwalker._tcp` services and resolves them one at
 * a time, since before Android 14 a second resolve fails while one is still running.
 */
internal class NsdTvBrowser(context: Context, private val log: (String) -> Unit) : TvBrowser {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)

    // From the application context: a WifiManager taken from an activity keeps that activity alive.
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun find(): Flow<TvAddress> = discover()
        .buffer(Channel.UNLIMITED) // services found while one is resolving wait their turn
        .mapNotNull { resolve(it) }

    /**
     * Each `_cloudwalker._tcp` service as it's found. Many phones drop multicast to save power, which
     * would lose the replies, so a multicast lock is held while looking and let go when the looking stops.
     */
    private fun discover(): Flow<NsdServiceInfo> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val lock = wifi?.createMulticastLock("BaronRemote mDNS")?.apply { setReferenceCounted(false) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS didn't start (error $errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            lock?.acquire()
            manager.discoverServices(CLOUDWALKER_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            log("mDNS didn't start: $e")
            close()
        }
        awaitClose {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (e: RuntimeException) {
                // It never started.
            }
            try {
                lock?.release()
            } catch (e: RuntimeException) {
                // Never held.
            }
        }
    }

    /** Where [service] answers, or null if that can't be worked out within [RESOLVE_LIMIT_MS]. */
    private suspend fun resolve(service: NsdServiceInfo): TvAddress? {
        val manager = nsd ?: return null
        return withTimeoutOrNull(RESOLVE_LIMIT_MS) {
            if (Build.VERSION.SDK_INT >= 34) watchService(manager, service) else resolveService(manager, service)
        }
    }

    @Suppress("DEPRECATION") // resolveService and NsdServiceInfo.host: the way to resolve before Android 14.
    private suspend fun resolveService(manager: NsdManager, service: NsdServiceInfo): TvAddress? {
        val answer = CompletableDeferred<TvAddress?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                answer.complete(addressOf(listOf(info.host), info.port))
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                log("Couldn't resolve ${info.serviceName} (error $errorCode)")
                answer.complete(null)
            }
        }
        try {
            manager.resolveService(service, listener)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return answer.await()
    }

    /** Android 14 and later: follow the service, and take the first update that carries an IPv4 address. */
    @RequiresApi(34)
    private suspend fun watchService(manager: NsdManager, service: NsdServiceInfo): TvAddress? {
        val answer = CompletableDeferred<TvAddress?>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                log("Couldn't resolve ${service.serviceName} (error $errorCode)")
                answer.complete(null)
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                addressOf(info.hostAddresses, info.port)?.let { answer.complete(it) }
            }

            override fun onServiceLost() {}

            override fun onServiceInfoCallbackUnregistered() {}
        }
        try {
            manager.registerServiceInfoCallback(service, Executor { it.run() }, callback)
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            return answer.await()
        } finally {
            try {
                manager.unregisterServiceInfoCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Registration had failed, so there's nothing to undo.
            }
        }
    }
}
