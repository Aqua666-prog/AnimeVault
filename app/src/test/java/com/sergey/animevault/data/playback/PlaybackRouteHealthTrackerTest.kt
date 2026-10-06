package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRouteHealthTrackerTest {
    @Test
    fun transientRouteNeedsTwoFailuresBeforeCooldown() {
        val tracker = PlaybackRouteHealthTracker()
        val variant = variant("cdn-a.example")
        val failure = PlaybackFailure(PlaybackFailureKind.NETWORK)

        tracker.recordFailure(variant, failure, nowMs = 1_000L)
        assertThat(tracker.isCoolingDown(variant, nowMs = 1_001L)).isFalse()

        tracker.recordFailure(variant, failure, nowMs = 2_000L)
        assertThat(tracker.isCoolingDown(variant, nowMs = 2_001L)).isTrue()
        assertThat(tracker.blockedHostFamilies(nowMs = 2_001L)).contains("cdn-a.example")
    }

    @Test
    fun dnsFailureCoolsRouteImmediately() {
        val tracker = PlaybackRouteHealthTracker()
        val variant = variant("dead.example")

        tracker.recordFailure(
            variant,
            PlaybackFailure(PlaybackFailureKind.DNS),
            nowMs = 10_000L,
        )

        assertThat(tracker.cooldownRemainingMs(variant, nowMs = 10_000L)).isEqualTo(60_000L)
    }

    @Test
    fun healthyPlaybackClearsCooldownAndFailureStreak() {
        val tracker = PlaybackRouteHealthTracker()
        val variant = variant("cdn-a.example")
        val failure = PlaybackFailure(PlaybackFailureKind.TIMEOUT)
        tracker.recordFailure(variant, failure, nowMs = 1_000L)
        tracker.recordFailure(variant, failure, nowMs = 2_000L)

        tracker.recordSuccess(variant, nowMs = 3_000L)

        assertThat(tracker.isCoolingDown(variant, nowMs = 3_001L)).isFalse()
        val state = tracker.snapshot().single()
        assertThat(state.consecutiveFailures).isEqualTo(0)
        assertThat(state.successfulRequests).isEqualTo(1)
    }

    @Test
    fun manualForgiveRemovesRoutePenalty() {
        val tracker = PlaybackRouteHealthTracker()
        val variant = variant("cdn-a.example")
        tracker.recordFailure(variant, PlaybackFailure(PlaybackFailureKind.DNS), nowMs = 1_000L)

        tracker.forgive(variant)

        assertThat(tracker.snapshot()).isEmpty()
        assertThat(tracker.isCoolingDown(variant, nowMs = 1_001L)).isFalse()
    }

    private fun variant(host: String) = PlaybackVariant(
        key = host,
        episodeKey = "episode:1",
        uri = "https://$host/video.m3u8",
        kind = PlaybackVariantKind.HLS,
        providerId = "provider",
        hostFamily = host,
    )
}
