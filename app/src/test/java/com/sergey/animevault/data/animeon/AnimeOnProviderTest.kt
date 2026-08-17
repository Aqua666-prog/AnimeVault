package com.sergey.animevault.data.animeon

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnimeOnProviderTest {
    @Test
    fun `search item maps ukrainian and english titles`() {
        val item = AnimeOnSearchItemDto(
            id = 952,
            season = 2,
            titleUa = "Магічна битва - 2 сезон",
            titleEn = "Jujutsu Kaisen Season 2",
            releaseDate = "2023-07-06",
            image = AnimeOnImageDto(preview = "posters/952.webp"),
        )

        val card = item.toCard()

        assertThat(card.id).isEqualTo("952")
        assertThat(card.name).isEqualTo("Магічна битва - 2 сезон")
        assertThat(card.englishName).isEqualTo("Jujutsu Kaisen Season 2")
        assertThat(card.year).isEqualTo(2023)
        assertThat(card.season).isEqualTo("Сезон 2")
        assertThat(card.posterUrl).contains("animeon.club/api/uploads/images/")
    }

    @Test
    fun `episode merger exposes every translation on the same episode`() {
        val glassMoon = AnimeOnTranslationOption(
            translationId = 10,
            playerId = 1,
            name = "Glass Moon",
            playerName = "Moon",
        )
        val starfall = AnimeOnTranslationOption(
            translationId = 20,
            playerId = 1,
            name = "Starfall",
            playerName = "Ashdi",
        )

        val episodes = mergeAnimeOnEpisodeSets(
            animeId = 13,
            episodeSets = listOf(
                glassMoon to listOf(
                    AnimeOnEpisodeDto(episode = 1, fileUrl = "https://cdn.example/gm-1.m3u8"),
                ),
                starfall to listOf(
                    AnimeOnEpisodeDto(episode = 1, fileUrl = "https://cdn.example/sf-1.m3u8"),
                ),
            ),
        )

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().streams.map { it.translation })
            .containsExactly("Glass Moon", "Starfall")
        assertThat(episodes.single().streams.all { it.type.name == "HLS" }).isTrue()
    }

    @Test
    fun `new api episode ids survive merging for lazy stream resolution`() {
        val translation = AnimeOnTranslationOption(
            translationId = 1191,
            playerId = 3847,
            name = "QTV AI Remaster",
            playerName = "Moon",
        )

        val episodes = mergeAnimeOnEpisodeSets(
            animeId = 913,
            episodeSets = listOf(
                translation to listOf(
                    AnimeOnEpisodeDto(
                        id = 45061,
                        episode = 1,
                        poster = "https://s2.mooncdn.net/poster.webp",
                    ),
                ),
            ),
        )

        val episode = episodes.single()
        assertThat(episode.hasStream).isTrue()
        assertThat(episode.previewUrl).contains("mooncdn.net")
        assertThat(decodeAnimeOnEpisodeRefs(episode.sourceRef)).containsExactly(
            AnimeOnEpisodeRef(
                episodeId = 45061,
                translationId = 1191,
                playerId = 3847,
                translationName = "QTV AI Remaster",
                playerName = "Moon",
            ),
        )
    }
}
