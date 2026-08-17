package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineCompletionTest {
    @Test
    fun shortPlaceholder_doesNotCompleteEpisode() {
        assertThat(isCredibleOnlineCompletion(positionMs = 900L, durationMs = 1_000L)).isFalse()
    }

    @Test
    fun earlyEnd_doesNotCompleteEpisode() {
        assertThat(isCredibleOnlineCompletion(positionMs = 5_000L, durationMs = 24 * 60_000L)).isFalse()
    }

    @Test
    fun realEpisodeEnd_completesEpisode() {
        val duration = 24 * 60_000L

        assertThat(isCredibleOnlineCompletion(positionMs = duration - 10_000L, durationMs = duration)).isTrue()
    }
}
