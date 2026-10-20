package com.barontv.home.core

import android.content.Intent

/** What the home screen's parts can ask of the activity that holds them. */
interface HomeHost {
    /** A short message at the bottom of the screen, as the prototype's toast. */
    fun toast(text: CharSequence)

    /** Opens an activity; says so in a toast and returns false if nothing answers. */
    fun launch(intent: Intent): Boolean

    /** Opens an installed app by package (its leanback launch intent first). */
    fun launchApp(packageName: String): Boolean
}
