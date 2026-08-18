package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class PlaybackCoreTest {
    @Test
    fun progressStates_areDerivedConsistently() {
        assertThat(PlaybackProgressSnapshot(0, 1_000, false).state).isEqualTo(WatchState.NOT_STARTED)
        assertThat(PlaybackProgressSnapshot(250, 1_000, false).state).isEqualTo(WatchState.IN_PROGRESS)
        assertThat(PlaybackProgressSnapshot(0, 1_000, true).state).isEqualTo(WatchState.COMPLETED)
    }

    @Test
    fun completionPolicy_preservesShippedNinetyTwoPercentThreshold() {
        val duration = 100_000L
        assertThat(PlaybackCompletionPolicy.normalize(91_999L, duration, ended = false).isCompleted).isFalse()
        assertThat(PlaybackCompletionPolicy.normalize(92_000L, duration, ended = false).isCompleted).isTrue()
    }

    @Test
    fun naturalEnd_rejectsShortPlaceholderAndAcceptsRealTail() {
        assertThat(PlaybackCompletionPolicy.isCredibleNaturalEnd(900L, 1_000L)).isFalse()
        val duration = 24 * 60_000L
        assertThat(PlaybackCompletionPolicy.isCredibleNaturalEnd(duration - 10_000L, duration)).isTrue()
    }

    @Test
    fun failureClassifier_distinguishesDnsAndTimeout() {
        assertThat(PlaybackFailureClassifier.classify(UnknownHostException()).kind)
            .isEqualTo(PlaybackFailureKind.DNS)
        assertThat(PlaybackFailureClassifier.classify(SocketTimeoutException()).kind)
            .isEqualTo(PlaybackFailureKind.TIMEOUT)
    }
}
