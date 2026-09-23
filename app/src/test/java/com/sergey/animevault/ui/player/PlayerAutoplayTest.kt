package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerAutoplayTest {
    @Test
    fun noNextEpisodeAlwaysStops() {
        NextEpisodeMode.entries.forEach { mode ->
            assertThat(nextEpisodeDecision<Long>(mode, null)).isEqualTo(NextEpisodeDecision.Stop)
        }
    }

    @Test
    fun offStopsEvenWhenNextExists() {
        assertThat(nextEpisodeDecision(NextEpisodeMode.OFF, 42L))
            .isEqualTo(NextEpisodeDecision.Stop)
    }

    @Test
    fun immediatePlaysNow() {
        assertThat(nextEpisodeDecision(NextEpisodeMode.IMMEDIATE, "episode-2"))
            .isEqualTo(NextEpisodeDecision.PlayNow("episode-2"))
    }

    @Test
    fun countdownDefersNavigation() {
        assertThat(nextEpisodeDecision(NextEpisodeMode.COUNTDOWN, 99L))
            .isEqualTo(NextEpisodeDecision.Countdown(99L))
    }
}
