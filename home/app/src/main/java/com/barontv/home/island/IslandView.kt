package com.barontv.home.island

import android.content.Context
import android.widget.FrameLayout
import com.barontv.home.core.HomeHost

/**
 * The now-playing island at the top centre: a black pill while something plays, opening into controls
 * when focused. It fills the island slot (the whole screen) and places itself.
 * (Placeholder: the island part replaces this.)
 */
class IslandView(context: Context, private val host: HomeHost) : FrameLayout(context)
