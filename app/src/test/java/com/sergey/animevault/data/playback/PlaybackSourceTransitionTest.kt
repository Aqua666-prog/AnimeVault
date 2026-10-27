package com.sergey.animevault.data.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceTransitionTest {
    private val snapshot = SourceTransitionSnapshot("old", 755000, 1400000, false, 1.25f)
    private fun event(state: PlaybackSession, value: SourceTransitionEvent) = PlaybackSessionReducer.reduce(state, PlaybackSessionEvent.SourceTransition(value))
    private fun begin(state: PlaybackSession = PlaybackSession(), key: String = "new", manual: Boolean = false) = event(state, SourceTransitionEvent.Begin(key, snapshot, manual))
    @Test fun successNeedsOrderedActualPreparationEvents() {
        var state = begin()
        val token = state.sourceTransition.token
        state = event(state, SourceTransitionEvent.Advance(token, SourceTransitionPhase.COMPLETED))
        assertEquals(SourceTransitionPhase.SNAPSHOTTING, state.sourceTransition.phase)
        listOf(SourceTransitionPhase.DISCOVERING, SourceTransitionPhase.VALIDATING, SourceTransitionPhase.AWAITING_CONFIRMATION,
            SourceTransitionPhase.PREPARING, SourceTransitionPhase.RESTORING, SourceTransitionPhase.COMPLETED).forEach {
            state = event(state, SourceTransitionEvent.Advance(token, it))
        }
        assertEquals(SourceTransitionPhase.COMPLETED, state.sourceTransition.phase)
        assertEquals(snapshot, state.sourceTransition.snapshot)
    }
    @Test fun cancelRestoresSnapshotPausesAndInvalidatesLateCallbacks() {
        val started = begin()
        val token = started.sourceTransition.token
        val cancelled = event(started, SourceTransitionEvent.Cancel(token))
        assertEquals(755000L, cancelled.positionMs)
        assertEquals("old", cancelled.variantKey)
        assertFalse(cancelled.playWhenReady)
        assertEquals(1.25f, cancelled.speed, 0.001f)
        assertEquals(cancelled, event(cancelled, SourceTransitionEvent.Advance(token, SourceTransitionPhase.PREPARING)))
        assertEquals(cancelled, event(cancelled, SourceTransitionEvent.Fail(token, "late error")))
    }
    @Test fun duplicateTapAndCyclicFallbackAreBounded() {
        val first = begin()
        assertEquals(first, begin(first, "other"))
        var state = event(first, SourceTransitionEvent.Fail(first.sourceTransition.token, "fixture"))
        assertEquals(state, begin(state))
        for (key in listOf("two", "three")) {
            state = begin(state, key)
            state = event(state, SourceTransitionEvent.Fail(state.sourceTransition.token, "fixture timeout"))
        }
        assertEquals(state, begin(state, "four"))
        assertEquals(SourceTransitionPhase.SNAPSHOTTING, begin(state, "four", manual = true).sourceTransition.phase)
    }
    @Test fun unknownDurationAndShortTimelineClampSafely() {
        assertEquals(755000L, safeSourceRestorePosition(755000, 0))
        assertEquals(9000L, safeSourceRestorePosition(755000, 10000))
        assertEquals(0L, safeSourceRestorePosition(-1, 10000))
    }
    @Test fun mediaPreparePreservesTheExistingOperation() {
        val first = begin(PlaybackSession(episodeKey = "episode"))
        val next = PlaybackSessionReducer.reduce(first, PlaybackSessionEvent.Prepare("episode", "new", 755000))
        assertEquals(first.sourceTransition, next.sourceTransition)
        assertEquals(PlaybackSourceTransition(), PlaybackSessionReducer.reduce(first, PlaybackSessionEvent.Prepare("another", "new", 0)).sourceTransition)
    }
}
