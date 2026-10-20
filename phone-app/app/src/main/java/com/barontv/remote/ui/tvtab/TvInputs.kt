package com.barontv.remote.ui.tvtab

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.barontv.remote.tv.TvBridge
import com.barontv.remote.tv.TvInput

/**
 * The TV's inputs until its new home has listed them: this TV always has these three. Shown as not
 * connected, which is also what the TV itself reports for every input (CLAUDE.md, "What this TV is like").
 */
internal val TheTvsInputs: List<TvInput> = listOf(
    TvInput("HDMI 1", live = false),
    TvInput("HDMI 2", live = false),
    TvInput("AV", live = false),
)

/**
 * What the TV's new home last said about its inputs, kept by the app's shell so the TV tab opens on the
 * last answer instead of flashing a different one.
 */
@Stable
internal class TvInputs(private val bridge: TvBridge, initial: List<TvInput>? = null) {
    /** The TV's inputs and whether something is plugged into each; null until the TV has listed them. */
    var list: List<TvInput>? by mutableStateOf(initial)
        private set

    /** Asks the TV again. An empty answer (no bridge, or no TV) keeps what it said last. */
    suspend fun refresh() {
        val inputs = bridge.inputs()
        if (inputs.isNotEmpty()) list = inputs
    }
}
