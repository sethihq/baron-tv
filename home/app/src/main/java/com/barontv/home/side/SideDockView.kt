package com.barontv.home.side

import android.content.Context
import android.widget.FrameLayout
import com.barontv.home.core.HomeHost

/**
 * The box on the right: Inputs (HDMI 1, HDMI 2, AV) and TV (Wi-Fi, Picture, Sound, Settings), whose Wi-Fi
 * and Sound tiles turn the whole box into their view. (Placeholder: the side dock part replaces this.)
 */
class SideDockView(context: Context, private val host: HomeHost) : FrameLayout(context) {
    /** Back while a Wi-Fi or Sound view is open closes it; returns true if it did. */
    fun onBack(): Boolean = false
}
