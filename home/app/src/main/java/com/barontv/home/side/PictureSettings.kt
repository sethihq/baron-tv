package com.barontv.home.side

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Where the Picture tile goes: the TV's own picture settings if this app can open them, else Android's
 * display settings, else Android's settings. CVTE documents no intent for its picture menu, so these are
 * tried in order and the first that resolves to an activity open to other apps wins.
 */
internal object PictureSettings {

    /** CVTE's TV settings (LiveTvGlobal, vendor/priv-app), the menu Picture lives in. */
    const val CVTE_PACKAGE = "com.cvte.tv.setting"

    /**
     * Its settings activity: the stock backup's getprop recorded it as the TV's foreground activity
     * (persist.sys.current.class) right after the HDMI player (persist.sys.prev.class), which is what the
     * remote's Menu opens over a source.
     */
    const val CVTE_ACTIVITY = "com.cvte.tv.setting.TvSettingActivity"

    /** The candidates in order (the TV app's own launch entry is added after its activity by [find]). */
    fun candidates(): List<Intent> = listOf(
        Intent().setClassName(CVTE_PACKAGE, CVTE_ACTIVITY),
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** The first candidate this app may open, or null. Call it off the main thread: it asks PackageManager. */
    fun find(context: Context): Intent? {
        val pm = context.packageManager
        val list = candidates().toMutableList()
        // If TvSettingActivity isn't open to other apps, the TV app's own entry point might be.
        (pm.getLeanbackLaunchIntentForPackage(CVTE_PACKAGE) ?: pm.getLaunchIntentForPackage(CVTE_PACKAGE))
            ?.let { list.add(1, it) }
        return list.firstOrNull { opens(context, it) }
    }

    private fun opens(context: Context, intent: Intent): Boolean {
        val info = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        } catch (e: RuntimeException) {
            null
        } ?: return false
        if (!info.exported) return false
        val permission = info.permission ?: return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
