package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackPrefetchPolicyTest {
    @Test
    fun `prefetch starts inside final thirty seconds`() {
        val duration = 24 * 60_000L

        assertThat(
            PlaybackPrefetchPolicy.shouldPrefetchNextEpisode(
                positionMs = duration - 20_000L,
                durationMs = duration,
                hasNextEpisode = true,
                alreadyPrefetched = false,
            ),
        ).isTrue()
    }

    @Test
    fun `prefetch starts after ninety seven percent for long episodes`() {
        val duration = 24 * 60_000L

        assertThat(
            PlaybackPrefetchPolicy.shouldPrefetchNextEpisode(
                positionMs = (duration * 0.975).toLong(),
                durationMs = duration,
                hasNextEpisode = true,
                alreadyPrefetched = false,
            ),
        ).isTrue()
    }

    @Test
    fun `prefetch stays off without a next episode`() {
        assertThat(
            PlaybackPrefetchPolicy.shouldPrefetchNextEpisode(
                positionMs = 1_400_000L,
                durationMs = 1_440_000L,
                hasNextEpisode = false,
                alreadyPrefetched = false,
            ),
        ).isFalse()
    }

    @Test
    fun `prefetch is one shot after success`() {
        assertThat(
            PlaybackPrefetchPolicy.shouldPrefetchNextEpisode(
                positionMs = 100_000L,
                durationMs = 110_000L,
                hasNextEpisode = true,
                alreadyPrefetched = true,
            ),
        ).isFalse()
    }
}
