package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSessionReducerTest {
    @Test
    fun variantSwitchPreservesTimelineAndMarksSwitchingState() {
        var state = PlaybackSessionReducer.reduce(
            PlaybackSession(),
            PlaybackSessionEvent.Prepare(
                episodeKey = "episode:7",
                variantKey = "720p",
                positionMs = 42_000L,
                durationMs = 1_400_000L,
            ),
        )
        state = PlaybackSessionReducer.reduce(
            state,
            PlaybackSessionEvent.SwitchVariant("1080p"),
        )

        assertThat(state.variantKey).isEqualTo("1080p")
        assertThat(state.positionMs).isEqualTo(42_000L)
        assertThat(state.durationMs).isEqualTo(1_400_000L)
        assertThat(state.phase).isEqualTo(PlaybackEnginePhase.SWITCHING_VARIANT)
    }

    @Test
    fun timelineNeverEscapesKnownDuration() {
        val state = PlaybackSessionReducer.reduce(
            PlaybackSession(durationMs = 100_000L),
            PlaybackSessionEvent.Timeline(
                positionMs = 150_000L,
                durationMs = 100_000L,
                bufferedPositionMs = 180_000L,
            ),
        )

        assertThat(state.positionMs).isEqualTo(100_000L)
        assertThat(state.durationMs).isEqualTo(100_000L)
    }

    @Test
    fun failureAndRecoveryAreExplicitStates() {
        val failed = PlaybackSessionReducer.reduce(
            PlaybackSession(phase = PlaybackEnginePhase.PLAYING),
            PlaybackSessionEvent.Failed(PlaybackFailure(PlaybackFailureKind.NETWORK)),
        )
        val ready = PlaybackSessionReducer.reduce(failed, PlaybackSessionEvent.Ready)

        assertThat(failed.phase).isEqualTo(PlaybackEnginePhase.ERROR)
        assertThat(failed.failure?.kind).isEqualTo(PlaybackFailureKind.NETWORK)
        assertThat(ready.phase).isEqualTo(PlaybackEnginePhase.READY)
        assertThat(ready.failure).isNull()
    }

    @Test
    fun speedIsClampedToSaneEngineRange() {
        val fast = PlaybackSessionReducer.reduce(
            PlaybackSession(),
            PlaybackSessionEvent.Speed(10f),
        )
        val slow = PlaybackSessionReducer.reduce(
            fast,
            PlaybackSessionEvent.Speed(0.01f),
        )

        assertThat(fast.speed).isEqualTo(4f)
        assertThat(slow.speed).isEqualTo(0.25f)
    }
}
