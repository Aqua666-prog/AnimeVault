package com.sergey.animevault.data.kodik

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class KodikMapperTest {
    private val response = Gson().fromJson(SAMPLE_RESPONSE, KodikResponseDto::class.java)

    @Test
    fun `catalog groups translations into one title card`() {
        val cards = response.results.toReleaseCards()

        assertThat(cards).hasSize(1)
        assertThat(cards.single().id).isEqualTo("shiki:5114")
        assertThat(cards.single().name).isEqualTo("Стальной алхимик: Братство")
        assertThat(cards.single().posterUrl).isEqualTo("https://cdn.example/poster.jpg")
        assertThat(cards.single().episodeCount).isEqualTo(2)
    }

    @Test
    fun `details combine voice and subtitles per episode`() {
        val details = response.results.toReleaseDetails(KodikReleaseReference.parse("shiki:5114"))

        assertThat(details.episodes).hasSize(2)
        assertThat(details.notification).isEqualTo("Озвучек: 1 · Субтитров: 1")
        assertThat(details.episodes.first().streams).hasSize(2)
        assertThat(details.episodes.first().streams.map { it.translation })
            .containsExactly("FumoDub", "Субтитры | CR").inOrder()
        assertThat(details.episodes.first().streams.map { it.type }.distinct())
            .containsExactly(OnlineStreamType.EMBED)
        assertThat(details.episodes.first().streams.first().url)
            .isEqualTo("https://kodik.info/episode/voice-1")
    }

    @Test
    fun `public token parser reads both assignment syntaxes`() {
        assertThat(extractKodikPublicToken("window.config={token: 'a1b2c3d4'}"))
            .isEqualTo("a1b2c3d4")
        assertThat(extractKodikPublicToken("const token=\"ffee1122\";"))
            .isEqualTo("ffee1122")
    }

    private companion object {
        val SAMPLE_RESPONSE = """
            {
              "total": 2,
              "results": [
                {
                  "id": "serial-voice",
                  "type": "anime-serial",
                  "title": "Стальной алхимик: Братство",
                  "title_orig": "Fullmetal Alchemist: Brotherhood",
                  "shikimori_id": "5114",
                  "last_season": 1,
                  "episodes_count": 2,
                  "quality": "WEB-DLRip 720p",
                  "translation": {"id": 101, "title": "FumoDub", "type": "voice"},
                  "material_data": {
                    "anime_title": "Стальной алхимик: Братство",
                    "title_en": "Fullmetal Alchemist: Brotherhood",
                    "anime_poster_url": "https://cdn.example/poster.jpg",
                    "anime_status": "released",
                    "anime_kind": "tv",
                    "episodes_aired": 2,
                    "duration": 24,
                    "anime_genres": ["Сёнен", "Приключения"]
                  },
                  "seasons": {
                    "1": {"episodes": {
                      "1": {"link": "//kodik.info/episode/voice-1", "title": "Железный город"},
                      "2": {"link": "//kodik.info/episode/voice-2", "title": "Первый день"}
                    }}
                  }
                },
                {
                  "id": "serial-subs",
                  "type": "anime-serial",
                  "title": "Стальной алхимик: Братство",
                  "shikimori_id": "5114",
                  "last_season": 1,
                  "episodes_count": 2,
                  "translation": {"id": 202, "title": "Субтитры | CR", "type": "subtitles"},
                  "seasons": {
                    "1": {"episodes": {
                      "1": {"link": "//kodik.info/episode/subs-1"},
                      "2": {"link": "//kodik.info/episode/subs-2"}
                    }}
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
