package com.sergey.animevault.ui.player

/**
 * Exclusive overlay state for the player chrome.
 *
 * Player screens used to keep an independent Boolean for every sheet/dialog. That makes
 * conflicting combinations possible (for example quality + speed + tracks) and makes the
 * chrome auto-hide timer depend on a long Boolean expression. This reducer guarantees that
 * at most one modal overlay owns player interaction at a time.
 */
enum class PlayerOverlay {
    SPEED,
    EQUALIZER,
    SKIP_SETTINGS,
    SLEEP_TIMER,
    TRACKS,
    VIDEO_SCALE,
    NEXT_EPISODE,
    QUALITY,
    EPISODE_PICKER,
}

data class PlayerOverlayState(
    val chromeVisible: Boolean = true,
    val active: PlayerOverlay? = null,
) {
    val hasModalOverlay: Boolean
        get() = active != null

    fun isOpen(overlay: PlayerOverlay): Boolean = active == overlay

    fun canAutoHide(
        playbackActive: Boolean,
        transientOverlayVisible: Boolean = false,
    ): Boolean = chromeVisible && playbackActive && !hasModalOverlay && !transientOverlayVisible

    fun shouldRenderChrome(
        forceVisible: Boolean = false,
        transientOverlayVisible: Boolean = false,
    ): Boolean = forceVisible || chromeVisible || hasModalOverlay || transientOverlayVisible
}

sealed interface PlayerOverlayEvent {
    data object ToggleChrome : PlayerOverlayEvent
    data object ShowChrome : PlayerOverlayEvent
    data object HideChrome : PlayerOverlayEvent
    data class Open(val overlay: PlayerOverlay) : PlayerOverlayEvent
    data class Dismiss(val overlay: PlayerOverlay? = null) : PlayerOverlayEvent
}

object PlayerOverlayReducer {
    fun reduce(state: PlayerOverlayState, event: PlayerOverlayEvent): PlayerOverlayState = when (event) {
        PlayerOverlayEvent.ToggleChrome -> {
            if (state.hasModalOverlay) state else state.copy(chromeVisible = !state.chromeVisible)
        }

        PlayerOverlayEvent.ShowChrome -> state.copy(chromeVisible = true)

        PlayerOverlayEvent.HideChrome -> {
            if (state.hasModalOverlay) state else state.copy(chromeVisible = false)
        }

        is PlayerOverlayEvent.Open -> state.copy(
            chromeVisible = true,
            active = event.overlay,
        )

        is PlayerOverlayEvent.Dismiss -> {
            if (event.overlay == null || event.overlay == state.active) {
                state.copy(chromeVisible = true, active = null)
            } else {
                state
            }
        }
    }
}
