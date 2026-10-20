package com.barontv.remote.ui

import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.tv.TvState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A pretend TV for previews and tests. It starts in [initial] and keeps to the [TvRemote] contract: a key
 * sent while not connected is dropped, and "Try again" goes back to looking. [show] moves it to any state.
 */
class FakeTvRemote(initial: TvState = TvState.Connected(name = "Living room TV", host = "192.168.1.25")) : TvRemote {
    private val current = MutableStateFlow(initial)
    private val keys = mutableListOf<TvKey>()

    override val state: StateFlow<TvState> = current.asStateFlow()

    /** The keys that reached the TV, oldest first. */
    val sent: List<TvKey> get() = keys.toList()

    /** How many times the screen asked to look for the TV again. */
    var retries = 0
        private set

    override fun send(key: TvKey) {
        if (current.value is TvState.Connected) keys += key
    }

    override fun retry() {
        retries++
        current.value = TvState.Searching
    }

    fun show(state: TvState) {
        current.value = state
    }
}
