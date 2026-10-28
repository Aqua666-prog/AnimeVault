package com.sergey.animevault.data.playback

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
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
    @Test fun semanticPreferencesRemainInSnapshotAndManualIntentSurvivesRecreation() {
        val profile = snapshot.copy(audioLanguages = listOf("ru"), textLanguages = listOf("en"),
            subtitlesDisabled = true, preferredQuality = null, translationKey = "voice-id")
        val store = PlaybackSessionStore()
        store.dispatch(PlaybackSessionEvent.SourceTransition(SourceTransitionEvent.Begin("target", profile, true)))
        val restoredUiState = store.state.value.sourceTransition
        assertTrue(restoredUiState.manualSelection)
        assertEquals(profile, restoredUiState.snapshot)
        store.dispatch(PlaybackSessionEvent.SourceTransition(SourceTransitionEvent.Cancel(restoredUiState.token)))
        assertEquals(profile, store.state.value.sourceTransition.snapshot)
        assertEquals(listOf("ru"), store.state.value.sourceTransition.snapshot?.audioLanguages)
    }
    @Test fun attemptBudgetOnlyResetsAfterConfirmedStablePlayback() {
        var state = begin()
        val token = state.sourceTransition.token
        assertEquals(state, event(state, SourceTransitionEvent.StablePlayback(token)))
        listOf(SourceTransitionPhase.DISCOVERING, SourceTransitionPhase.VALIDATING, SourceTransitionPhase.PREPARING,
            SourceTransitionPhase.RESTORING, SourceTransitionPhase.COMPLETED).forEach { state = event(state, SourceTransitionEvent.Advance(token, it)) }
        assertEquals(1, state.sourceTransition.attemptedKeys.size)
        val stable = event(state, SourceTransitionEvent.StablePlayback(token))
        assertTrue(stable.sourceTransition.attemptedKeys.isEmpty())
        assertEquals(state, event(state, SourceTransitionEvent.StablePlayback(token - 1)))
    }
    @Test fun mediaPreparePreservesTheExistingOperation() {
        val first = begin(PlaybackSession(episodeKey = "episode"))
        val next = PlaybackSessionReducer.reduce(first, PlaybackSessionEvent.Prepare("episode", "new", 755000))
        assertEquals(first.sourceTransition, next.sourceTransition)
        assertEquals(PlaybackSourceTransition(), PlaybackSessionReducer.reduce(first, PlaybackSessionEvent.Prepare("another", "new", 0)).sourceTransition)
    }
    @Test fun failedMediaSourceCannotPublishLateReadyOrProgress() {
        val preparing = begin(PlaybackSession(episodeKey = "episode", variantKey = "new"))
        val token = preparing.sourceTransition.token
        val failed = event(preparing, SourceTransitionEvent.Fail(token, "fixture MediaSource failure"))
        assertFalse(failed.acceptsSourceCallbacks("episode", "new", token))
        assertFalse(failed.canPersistSourceProgress())
        assertEquals(failed, event(failed, SourceTransitionEvent.Advance(token, SourceTransitionPhase.RESTORING)))
        val rollback = event(failed, SourceTransitionEvent.Cancel(token))
        assertTrue(rollback.acceptsSourceCallbacks("episode", "old", rollback.sourceTransition.token))
        assertFalse(rollback.acceptsSourceCallbacks("episode", "new", token))
        assertFalse(rollback.acceptsSourceCallbacks("next episode", "old", rollback.sourceTransition.token))
        assertEquals(snapshot.positionMs, rollback.positionMs)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun twentySecondDeadlineFailsPreparationAndCancellationStopsOldDeadline() = kotlinx.coroutines.test.runTest {
        var state = begin(PlaybackSession(episodeKey = "episode", variantKey = "new"))
        val token = state.sourceTransition.token
        val job = launch {
            sourcePreparationTimeout(token) { state = event(state, it) }
        }
        runCurrent()
        advanceTimeBy(19_999)
        assertTrue(state.sourceTransition.busy)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SourceTransitionPhase.FAILED, state.sourceTransition.phase)
        assertFalse(state.acceptsSourceCallbacks("episode", "new", token))
        job.join()
        state = event(state, SourceTransitionEvent.Cancel(token))
        val before = state
        val cancelledJob = launch {
            sourcePreparationTimeout(state.sourceTransition.token) { state = event(state, it) }
        }
        runCurrent()
        cancelledJob.cancel()
        advanceTimeBy(20_001)
        assertEquals(before, state)
    }
    @Test fun unpreparedAndErrorTimelinesKeepResumeButValidSeekToZeroIsAllowed() {
        val original = PlaybackSession(episodeKey = "episode", variantKey = "old", positionMs = 755000,
            durationMs = 1400000, phase = PlaybackEnginePhase.PREPARING)
        val noTimeline = PlaybackSessionReducer.reduce(original, PlaybackSessionEvent.Timeline(0, 0, 0, credible = false))
        assertEquals(755000L, noTimeline.positionMs)
        assertEquals(1400000L, noTimeline.durationMs)
        val failed = PlaybackSessionReducer.reduce(noTimeline, PlaybackSessionEvent.Failed(PlaybackFailure(PlaybackFailureKind.NETWORK)))
        assertEquals(755000L, PlaybackSessionReducer.reduce(failed,
            PlaybackSessionEvent.Timeline(0, 1400000, 0, credible = false)).positionMs)
        assertEquals(0L, PlaybackSessionReducer.reduce(original,
            PlaybackSessionEvent.Timeline(0, 1400000, 0, credible = true)).positionMs)
    }
}
