package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnimeThemeModelsTest {
    @Test
    fun `AnimeThemes episode range is localized`() {
        assertThat(localizeAnimeThemesEpisodes("1-13")).isEqualTo("серии 1–13")
        assertThat(localizeAnimeThemesEpisodes("2-")).isEqualTo("серии 2–")
    }

    @Test
    fun `search matcher accepts exact english title and matching year`() {
        val animeThemesId = chooseAnimeThemesCandidate(
            names = listOf("Атака титанов", "Shingeki no Kyojin"),
            year = 2013,
            type = "TV",
            candidates = listOf(
                AnimeThemesSearchCandidate(
                    animeThemesId = 2611,
                    titles = listOf("Shingeki no Kyojin", "Attack on Titan"),
                    year = 2013,
                    type = "TV",
                ),
            ),
        )

        assertThat(animeThemesId).isEqualTo(2611)
    }

    @Test
    fun `search matcher rejects weak fuzzy coincidence`() {
        val animeThemesId = chooseAnimeThemesCandidate(
            names = listOf("Fate"),
            year = 2026,
            type = "TV",
            candidates = listOf(
                AnimeThemesSearchCandidate(
                    animeThemesId = 1,
                    titles = listOf("Fate stay night Unlimited Blade Works"),
                    year = 2014,
                    type = "TV",
                ),
            ),
        )

        assertThat(animeThemesId).isNull()
    }
}
