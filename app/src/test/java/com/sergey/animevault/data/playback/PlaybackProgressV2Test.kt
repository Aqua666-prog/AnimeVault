package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackProgressV2Test {
    @Test
    fun longEpisodeCompletesInsideNinetySecondTail() {
        val duration = 24 * 60_000L
        assertThat(PlaybackCompletionPolicy.isPastCompletionThreshold(duration - 60_000L, duration)).isTrue()
    }

    @Test
    fun shortClipDoesNotCompleteOnlyBecauseTailIsSmall() {
        val duration = 5 * 60_000L
        assertThat(PlaybackCompletionPolicy.isPastCompletionThreshold(duration - 90_000L, duration)).isFalse()
    }

    @Test
    fun ninetyPercentCompletes() {
        val duration = 20 * 60_000L
        assertThat(PlaybackCompletionPolicy.isPastCompletionThreshold((duration * 0.90).toLong(), duration)).isTrue()
    }
}
