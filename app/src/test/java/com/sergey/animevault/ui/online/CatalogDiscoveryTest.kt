package com.sergey.animevault.ui.online

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineReleaseCard
import org.junit.Test

class CatalogDiscoveryTest {
    @Test
    fun `жанры собираются без дублей и сортируются`() {
        val releases = listOf(
            card("a", "Первый", genres = listOf("Фэнтези", "Комедия")),
            card("b", "Второй", genres = listOf("фэнтези", "Романтика")),
        )

        assertThat(availableCatalogGenres(releases))
            .containsExactly("Комедия", "Романтика", "Фэнтези")
            .inOrder()
    }

    @Test
    fun `жанровый фильтр можно совместить с тематической подборкой`() {
        val releases = listOf(
            card("a", "Фэнтези-комедия", genres = listOf("Фэнтези", "Комедия")),
            card("b", "Романтика", genres = listOf("Романтика")),
            card("c", "Другое фэнтези", genres = listOf("Фэнтези")),
        )

        val result = discoverCatalog(
            releases = releases,
            selectedGenre = "Комедия",
            collection = ThematicCollection.FANTASY,
            sort = CatalogSort.SOURCE,
            currentYear = 2026,
        )

        assertThat(result.map(OnlineReleaseCard::name)).containsExactly("Фэнтези-комедия")
    }

    @Test
    fun `подборки распознают онгоинги новинки и короткие истории`() {
        val ongoing = card("a", "Онгоинг", year = 2026, episodes = 8, ongoing = true)
        val oldLong = card("b", "Старый", year = 2010, episodes = 24)

        assertThat(ThematicCollection.ONGOING.matches(ongoing, 2026)).isTrue()
        assertThat(ThematicCollection.NEW_RELEASES.matches(ongoing, 2026)).isTrue()
        assertThat(ThematicCollection.SHORT.matches(ongoing, 2026)).isTrue()
        assertThat(ThematicCollection.NEW_RELEASES.matches(oldLong, 2026)).isFalse()
        assertThat(ThematicCollection.SHORT.matches(oldLong, 2026)).isFalse()
    }

    @Test
    fun `сортировка по жанрам стабильна и учитывает название`() {
        val releases = listOf(
            card("c", "Яблоко", genres = listOf("Фэнтези")),
            card("a", "Арбуз", genres = listOf("Комедия")),
            card("b", "Банан", genres = listOf("Фэнтези")),
        )

        val result = discoverCatalog(
            releases = releases,
            selectedGenre = null,
            collection = ThematicCollection.ALL,
            sort = CatalogSort.GENRE,
            currentYear = 2026,
        )

        assertThat(result.map(OnlineReleaseCard::name))
            .containsExactly("Арбуз", "Банан", "Яблоко")
            .inOrder()
    }

    @Test
    fun `пустые тематические подборки не показываются`() {
        val options = availableCollections(
            releases = listOf(card("a", "Драма", genres = listOf("Драма"))),
            currentYear = 2026,
        )

        assertThat(options.map { it.collection }).contains(ThematicCollection.ALL)
        assertThat(options.map { it.collection }).doesNotContain(ThematicCollection.FANTASY)
    }

    private fun card(
        id: String,
        name: String,
        year: Int? = 2024,
        episodes: Int? = 12,
        ongoing: Boolean = false,
        genres: List<String> = emptyList(),
    ) = OnlineReleaseCard(
        providerId = "test",
        providerName = "Тест",
        id = id,
        alias = id,
        name = name,
        englishName = null,
        posterUrl = null,
        year = year,
        type = "TV",
        season = null,
        episodeCount = episodes,
        isOngoing = ongoing,
        genres = genres,
    )
}
