package com.sergey.animevault.ui.online

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.ui.preferences.CatalogAdvancedFilter
import com.sergey.animevault.ui.preferences.GenreMatchMode
import org.junit.Test

class CatalogAdvancedDiscoveryTest {
    private fun card(id: String, year: Int?, episodes: Int?, genres: List<String>) = OnlineReleaseCard(
        providerId = "p", providerName = "Provider", id = id, alias = id, name = id,
        englishName = null, posterUrl = null, year = year, type = "TV", season = null,
        episodeCount = episodes, isOngoing = false, genres = genres,
    )

    @Test fun allAnyExcludeAndUnknownsWork() {
        val anime = listOf(card("1", 2026, 12, listOf("Романтика", "Спорт")),
            card("2", 2020, null, listOf("Романтика")),
            card("3", null, 20, listOf("Спорт", "Ужасы")))
        val filter = CatalogAdvancedFilter(genres = setOf("романтика", "спорт"),
            genreMode = GenreMatchMode.ALL, yearFrom = 2025, episodesTo = 13)
        assertThat(advancedCatalogFilter(anime, filter, emptySet()).map { it.id }).containsExactly("1")
        assertThat(advancedCatalogFilter(anime, filter.copy(genreMode = GenreMatchMode.ANY,
            yearFrom = null, episodesTo = null, excludedGenres = setOf("ужасы")), emptySet()).map { it.id })
            .containsExactly("1", "2")
    }

    @Test fun excludeUserListsByProviderQualifiedKey() {
        val anime = listOf(card("7", 2026, 12, emptyList()))
        val filter = CatalogAdvancedFilter(excludeMyLists = true)
        assertThat(advancedCatalogFilter(anime, filter, setOf("online:p:7"))).isEmpty()
        assertThat(advancedCatalogFilter(anime, filter, setOf("online:other:7"))).hasSize(1)
    }
}
