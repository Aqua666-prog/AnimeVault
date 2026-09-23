package com.sergey.animevault.ui.online

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineReleaseCard
import org.junit.Test

class CatalogFiltersV2Test {
    private val releases = listOf(
        card("A", 2026, "TV", 12, true),
        card("B", 2024, "Movie", 1, false),
        card("C", 2026, "TV", 24, false),
        card("D", 2023, "TV", 50, false),
    )

    @Test fun combinesYearStatusTypeAndEpisodeRange() {
        val result = discoverCatalog(
            releases, null, ThematicCollection.ALL, CatalogSort.SOURCE,
            selectedYear = 2026,
            selectedType = "TV",
            status = CatalogStatusFilter.FINISHED,
            episodes = CatalogEpisodeFilter.STANDARD,
            currentYear = 2026,
        )
        assertThat(result.map { it.name }).containsExactly("C")
    }

    @Test fun derivesYearsAndTypes() {
        assertThat(availableCatalogYears(releases)).containsExactly(2026, 2024, 2023).inOrder()
        assertThat(availableCatalogTypes(releases)).containsExactly("Movie", "TV").inOrder()
    }

    private fun card(name: String, year: Int, type: String, episodes: Int, ongoing: Boolean) = OnlineReleaseCard(
        providerId = "p", providerName = "P", id = name, alias = name, name = name,
        englishName = null, posterUrl = null, year = year, type = type, season = null,
        episodeCount = episodes, isOngoing = ongoing, genres = listOf("Action"),
    )
}
