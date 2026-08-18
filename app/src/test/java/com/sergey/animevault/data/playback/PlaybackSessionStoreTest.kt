package com.sergey.animevault.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionStoreTest {
    @Test
    fun store_preservesTimelineAcrossVariantSwitch() {
        val store = PlaybackSessionStore()
        store.dispatch(
            PlaybackSessionEvent.Prepare(
                episodeKey = "episode:1",
                variantKey = "720p",
                positionMs = 42_000L,
                durationMs = 1_440_000L,
            ),
        )
        store.dispatch(
            PlaybackSessionEvent.Timeline(
                positionMs = 73_500L,
                durationMs = 1_440_000L,
                bufferedPositionMs = 95_000L,
            ),
        )
        store.dispatch(PlaybackSessionEvent.SwitchVariant("1080p"))

        val session = store.state.value
        assertEquals("1080p", session.variantKey)
        assertEquals(73_500L, session.positionMs)
        assertEquals(1_440_000L, session.durationMs)
        assertEquals(PlaybackEnginePhase.SWITCHING_VARIANT, session.phase)
    }

    @Test
    fun prepareAfterFailure_clearsPreviousFailure() {
        val store = PlaybackSessionStore()
        store.dispatch(
            PlaybackSessionEvent.Failed(
                PlaybackFailure(PlaybackFailureKind.TIMEOUT, "timeout"),
            ),
        )
        store.dispatch(
            PlaybackSessionEvent.Prepare(
                episodeKey = "episode:1",
                variantKey = "fallback",
                positionMs = 10_000L,
            ),
        )

        assertNull(store.state.value.failure)
        assertEquals(PlaybackEnginePhase.PREPARING, store.state.value.phase)
    }
}
