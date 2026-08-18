package com.sergey.animevault.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOverlayStateTest {
    @Test
    fun openOverlay_isExclusiveAndKeepsChromeVisible() {
        val speed = PlayerOverlayReducer.reduce(
            PlayerOverlayState(chromeVisible = false),
            PlayerOverlayEvent.Open(PlayerOverlay.SPEED),
        )
        val quality = PlayerOverlayReducer.reduce(
            speed,
            PlayerOverlayEvent.Open(PlayerOverlay.QUALITY),
        )

        assertTrue(quality.chromeVisible)
        assertEquals(PlayerOverlay.QUALITY, quality.active)
        assertFalse(quality.isOpen(PlayerOverlay.SPEED))
    }

    @Test
    fun chromeCannotHideWhileModalOwnsInteraction() {
        val open = PlayerOverlayState(
            chromeVisible = true,
            active = PlayerOverlay.TRACKS,
        )

        assertEquals(open, PlayerOverlayReducer.reduce(open, PlayerOverlayEvent.HideChrome))
        assertEquals(open, PlayerOverlayReducer.reduce(open, PlayerOverlayEvent.ToggleChrome))
        assertFalse(open.canAutoHide(playbackActive = true))
    }

    @Test
    fun dismissOnlyClosesRequestedOverlay() {
        val open = PlayerOverlayState(active = PlayerOverlay.VIDEO_SCALE)
        val wrongDismiss = PlayerOverlayReducer.reduce(
            open,
            PlayerOverlayEvent.Dismiss(PlayerOverlay.SPEED),
        )
        val correctDismiss = PlayerOverlayReducer.reduce(
            wrongDismiss,
            PlayerOverlayEvent.Dismiss(PlayerOverlay.VIDEO_SCALE),
        )

        assertEquals(PlayerOverlay.VIDEO_SCALE, wrongDismiss.active)
        assertEquals(null, correctDismiss.active)
        assertTrue(correctDismiss.chromeVisible)
    }

    @Test
    fun autoHideRequiresPlaybackAndNoTransientOverlay() {
        val state = PlayerOverlayState(chromeVisible = true)

        assertTrue(state.canAutoHide(playbackActive = true))
        assertFalse(state.canAutoHide(playbackActive = false))
        assertFalse(state.canAutoHide(playbackActive = true, transientOverlayVisible = true))
    }
}
