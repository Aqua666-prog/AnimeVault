package com.sergey.animevault.ui.history

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LocalHistoryRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import org.junit.Test

class HistoryViewModelTest {
    @Test
    fun allFilter_mergesLocalAndOnlineByTimestamp() {
        val local = listOf(localRow(timestamp = 200L))
        val online = listOf(onlineEntry(timestamp = 300L))

        val state = buildHistoryUiState(local, online, HistoryFilter.ALL)

        assertThat(state.items).hasSize(2)
        assertThat(state.items.first()).isInstanceOf(HistoryItem.Online::class.java)
        assertThat(state.localCount).isEqualTo(1)
        assertThat(state.onlineCount).isEqualTo(1)
    }

    @Test
    fun localFilter_keepsCountsButShowsOnlyLocalRows() {
        val local = listOf(localRow(timestamp = 200L))
        val online = listOf(onlineEntry(timestamp = 300L))

        val state = buildHistoryUiState(local, online, HistoryFilter.LOCAL)

        assertThat(state.items).containsExactly(HistoryItem.Local(local.single()))
        assertThat(state.localCount).isEqualTo(1)
        assertThat(state.onlineCount).isEqualTo(1)
    }

    @Test
    fun onlineEntriesWithoutHistory_areIgnored() {
        val untouched = OnlineLibraryEntry(
            providerId = "kodik",
            providerName = "Kodik",
            releaseId = "release-2",
            name = "Untouched",
        )

        val state = buildHistoryUiState(emptyList(), listOf(untouched), HistoryFilter.ALL)

        assertThat(state.items).isEmpty()
        assertThat(state.onlineCount).isEqualTo(0)
    }

    private fun localRow(timestamp: Long) = LocalHistoryRow(
        episodeId = 1L,
        titleId = 10L,
        titleName = "Local title",
        posterUri = null,
        episodeNumber = 2.0,
        seasonNumber = 1,
        positionMs = 1_000L,
        durationMs = 2_000L,
        isCompleted = false,
        lastWatchedAt = timestamp,
    )

    private fun onlineEntry(timestamp: Long) = OnlineLibraryEntry(
        providerId = "aniliberty",
        providerName = "AniLiberty",
        releaseId = "release-1",
        name = "Online title",
        lastOpenedAt = timestamp,
    )
}
