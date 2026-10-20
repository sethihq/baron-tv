package com.barontv.remote.tv

import kotlinx.coroutines.flow.StateFlow

/**
 * The keys the phone sends. The codes are Android key codes, which CloudWalker's phone-remote server on
 * the TV (`tv.cloudwalker.inputserver`, port 5511) injects as they are: `GET /input/?keycode=N`.
 *
 * Input (178) is sent like the rest, but the TV may ignore it: `input`-style injected keys skip the
 * accessibility service that turns the remote's Input key into CVTE's source panel (see CLAUDE.md,
 * "Keys"). The launcher's own server will open the panel directly in a later step.
 */
enum class TvKey(val code: Int) {
    UP(19),
    DOWN(20),
    LEFT(21),
    RIGHT(22),
    OK(23),
    BACK(4),
    HOME(3),
    MENU(82),
    INPUT(178),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    MUTE(164),
    PLAY_PAUSE(85),
}

/** Where the phone is with the TV. */
sealed interface TvState {
    /** Looking for the TV on this Wi-Fi (by its mDNS name first, then by asking every address). */
    data object Searching : TvState

    /** Found and answering. [name] is what the screen calls it, [host] its address on the Wi-Fi. */
    data class Connected(val name: String, val host: String) : TvState

    /** The phone isn't on Wi-Fi, so there's no TV to look for. */
    data object NoWifi : TvState

    /**
     * Searched and nothing answered: the TV is off, on another network, or its remote server is asleep
     * (after a reboot it stays stopped until something opens it once).
     */
    data object NotFound : TvState
}

/** The TV, as the remote screen sees it. */
interface TvRemote {
    val state: StateFlow<TvState>

    /** Sends one key press. Never blocks the caller; a press while not connected is dropped. */
    fun send(key: TvKey)

    /** Looks for the TV again (the screen's "Try again"). */
    fun retry()

    /** The app is on screen: find the TV and keep the connection checked. */
    fun start() {}

    /** The app left the screen: stop searching and checking. */
    fun stop() {}
}
