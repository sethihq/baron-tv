package com.barontv.home.input

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps the Input key's accessibility service switched on. It carries the Input key, the phone's bridge,
 * music mode over apps and the volume bar, and Android quietly drops it from the enabled list when its
 * package is force-stopped, and (seen on 2026-10-21) when a system app was removed from the TV. So the home
 * puts it back: when it starts, and whenever the setting changes while the home's process is alive.
 * Writing the setting needs WRITE_SECURE_SETTINGS, granted over adb by home/build-and-install.sh.
 */
object ServiceGuard {
    private const val TAG = "BaronGuard"
    private var observer: ContentObserver? = null
    private var warned = false

    fun start(context: Context) {
        val app = context.applicationContext
        ensure(app)
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ensure(app)
            }
        }
        observer = o
        val resolver = app.contentResolver
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, o)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, o)
    }

    /** Adds the service to the enabled list (keeping any others) and turns accessibility on, if needed. */
    fun ensure(context: Context) {
        val me = ComponentName(context, InputKeyService::class.java).flattenToString()
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val others = enabled.split(':').filter { it.isNotBlank() && it != "null" && it != me && !it.startsWith(OLD_LAUNCHER) }
        val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val present = enabled.split(':').any { it == me }
        if (present && on) return
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Switched the Input key service back on")
        } catch (e: SecurityException) {
            if (!warned) {
                warned = true
                Log.w(TAG, "Can't switch the Input key service back on (WRITE_SECURE_SETTINGS not granted)")
            }
        }
    }

    // The old launcher's own Input key service answers the same key: only one of the two may be on.
    private const val OLD_LAUNCHER = "com.gathin.tvhome/"
}
