package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackProgressMergerTest {
    @Test
    fun completedTransportWins() {
        val online = PlaybackProgressSnapshot(50_000L, 100_000L, false, 200L)
        val local = PlaybackProgressSnapshot(0L, 100_000L, true, 100L)

        assertThat(PlaybackProgressMerger.choose(online, local)).isEqualTo(local)
    }

    @Test
    fun furthestProgressWinsBeforeTimestamp() {
        val online = PlaybackProgressSnapshot(70_000L, 100_000L, false, 100L)
        val local = PlaybackProgressSnapshot(40_000L, 100_000L, false, 200L)

        assertThat(PlaybackProgressMerger.choose(online, local)).isEqualTo(online)
    }

    @Test
    fun latestTimestampBreaksExactTie() {
        val online = PlaybackProgressSnapshot(40_000L, 100_000L, false, 100L)
        val local = PlaybackProgressSnapshot(40_000L, 100_000L, false, 200L)

        assertThat(PlaybackProgressMerger.choose(online, local)).isEqualTo(local)
    }
}
