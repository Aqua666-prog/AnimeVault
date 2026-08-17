package com.sergey.animevault.data.aniliberty

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AniLibertyMapperTest {
    @Test
    fun catalogJson_mapsPosterAndSeasonCard() {
        val json =
            """
            {
              "id": 10165,
              "alias": "bookworm",
              "year": 2026,
              "name": {"main": "Революция книжного червя", "english": "Bookworm"},
              "type": {"value": "TV", "description": "ТВ"},
              "season": {"value": "spring", "description": "Весна"},
              "poster": {
                "thumbnail": "/poster.jpg",
                "optimized": {"thumbnail": "/poster.webp"}
              },
              "episodes_total": 24,
              "is_ongoing": true
            }
            """.trimIndent()

        val card = Gson().fromJson(json, ReleaseDto::class.java).toCard()

        assertThat(card.id).isEqualTo("10165")
        assertThat(card.providerId).isEqualTo("aniliberty")
        assertThat(card.name).isEqualTo("Революция книжного червя")
        assertThat(card.posterUrl).isEqualTo("https://aniliberty.top/poster.webp")
        assertThat(card.season).isEqualTo("Весна")
        assertThat(card.episodeCount).isEqualTo(24)
        assertThat(card.isOngoing).isTrue()
    }

    @Test
    fun releaseJson_mapsAndOrdersEpisodesWithQualityOptions() {
        val json =
            """
            {
              "id": 42,
              "name": {"main": "Тестовый релиз"},
              "episodes": [
                {
                  "id": "episode-2",
                  "release_id": 42,
                  "ordinal": 2,
                  "sort_order": 2,
                  "duration": 1200,
                  "hls_480": "https://video/2-480.m3u8"
                },
                {
                  "id": "episode-1",
                  "release_id": 42,
                  "ordinal": 1,
                  "sort_order": 1,
                  "duration": 1430,
                  "hls_480": "https://video/1-480.m3u8",
                  "hls_720": "https://video/1-720.m3u8",
                  "hls_1080": "https://video/1-1080.m3u8"
                }
              ]
            }
            """.trimIndent()

        val release = Gson().fromJson(json, ReleaseDto::class.java).toDetails()

        assertThat(release.episodes.map(OnlineEpisode::id))
            .containsExactly("episode-1", "episode-2")
            .inOrder()
        assertThat(release.episodes.first().streams.map(OnlineStream::quality))
            .containsExactly(1080, 720, 480)
            .inOrder()
        assertThat(release.episodes.first().durationMs).isEqualTo(1_430_000L)
    }

    @Test
    fun absoluteImageUrl_preservesAbsoluteAndExpandsRelativePaths() {
        assertThat("/storage/poster.webp".absoluteImageUrl())
            .isEqualTo("https://aniliberty.top/storage/poster.webp")
        assertThat("https://cdn.example/poster.webp".absoluteImageUrl())
            .isEqualTo("https://cdn.example/poster.webp")
    }
}
