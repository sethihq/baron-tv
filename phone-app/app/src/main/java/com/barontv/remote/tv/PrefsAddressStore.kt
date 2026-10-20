package com.barontv.remote.tv

import android.content.Context
import android.content.SharedPreferences

/** The TV's last good address, kept in SharedPreferences so the next launch can try it first. */
internal class PrefsAddressStore(context: Context) : AddressStore {
    // Opened on first use, which is on a background thread rather than while the activity starts.
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("tv", Context.MODE_PRIVATE) }

    override fun load(): TvAddress? {
        val host = prefs.getString(HOST, null) ?: return null
        return TvAddress(host, prefs.getInt(PORT, CLOUDWALKER_PORT))
    }

    override fun save(address: TvAddress) {
        prefs.edit().putString(HOST, address.host).putInt(PORT, address.port).apply()
    }

    private companion object {
        const val HOST = "host"
        const val PORT = "port"
    }
}
