package com.sergey.animevault.ui.library

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.ui.preferences.VaultTitleList
import com.sergey.animevault.ui.preferences.vaultLocalListKey
import com.sergey.animevault.ui.preferences.vaultOnlineListKey
import org.junit.Test

class LibraryHubTest {
    @Test
    fun favoriteAndListMembershipStayIndependentAcrossSources() {
        val online = OnlineLibraryEntry("provider", "Источник", "7", "Аниме", isFavorite = true)
        val items = libraryHubItems(listOf(local(7)), listOf(online),
            mapOf(vaultOnlineListKey("provider", "7") to VaultTitleList.PLANNED), setOf(7L))
        assertThat(items).hasSize(2)
        assertThat(items.map(LibraryHubItem::key).distinct()).hasSize(2)
        assertThat(items.all(LibraryHubItem::favorite)).isTrue()
        assertThat(items.single { it.localId != null }.list).isEqualTo(VaultTitleList.NONE)
        assertThat(items.single { it.providerId != null }.list).isEqualTo(VaultTitleList.PLANNED)
    }

    @Test
    fun explicitListWinsOverLocalCompletionButZeroEpisodeTitleIsNotWatched() {
        val completed = local(1, 12, 12)
        val items = libraryHubItems(listOf(completed, local(2, 0, 0), local(3, 12, 12)), emptyList(),
            mapOf(vaultLocalListKey(1) to VaultTitleList.DROPPED), emptySet())
        assertThat(items.single { it.localId == 1L }.list).isEqualTo(VaultTitleList.DROPPED)
        assertThat(items.single { it.localId == 2L }.list).isEqualTo(VaultTitleList.NONE)
        assertThat(items.single { it.localId == 3L }.list).isEqualTo(VaultTitleList.WATCHED)
    }

    @Test
    fun finishingOneOnlineEpisodeDoesNotMarkEntireTitleWatched() {
        val entry = OnlineLibraryEntry("provider", "Источник", "7", "Аниме", episodeCount = 12,
            lastEpisodeId = "1", lastEpisodeCompleted = true)
        assertThat(libraryHubItems(emptyList(), listOf(entry), emptyMap(), emptySet()).single().list)
            .isEqualTo(VaultTitleList.NONE)
    }

    private fun local(id: Long, total: Long = 12, completed: Long = 0) = LibraryTitleRow(
        id, "Аниме", null, 0, total, completed, null, 0)
}
