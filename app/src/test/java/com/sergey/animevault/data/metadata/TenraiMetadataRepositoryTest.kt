package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class TenraiMetadataRepositoryTest {
    private val gson = Gson()

    @Test
    fun fullParser_mapsOverviewRelationsThemesAndLinks() {
        val overview = parseTenraiFull(
            gson,
            """
            {
              "data": {
                "mal_id": 52991,
                "title": "Sousou no Frieren",
                "title_english": "Frieren: Beyond Journey's End",
                "title_japanese": "葬送のフリーレン",
                "images": {
                  "jpg": {"image_url":"https://img/poster.jpg","large_image_url":"https://img/poster-large.jpg"},
                  "webp": {"image_url":"https://img/poster.webp","large_image_url":"https://img/poster-large.webp"}
                },
                "trailer": {
                  "youtube_id":"abc123",
                  "url":"https://youtube.test/watch?v=abc123",
                  "embed_url":"https://youtube.test/embed/abc123",
                  "images":{"maximum_image_url":"https://img/trailer.jpg"}
                },
                "type":"TV",
                "source":"Manga",
                "episodes":28,
                "status":"Finished Airing",
                "airing":false,
                "duration":"24 min per ep",
                "rating":"PG-13",
                "score":9.31,
                "scored_by":500000,
                "rank":1,
                "popularity":20,
                "members":900000,
                "favorites":60000,
                "synopsis":"Journey",
                "background":"Background",
                "season":"fall",
                "year":2023,
                "broadcast":{"string":"Fridays at 23:00 (JST)"},
                "studios":[{"mal_id":11,"name":"Madhouse","url":"https://mal/studio/11"}],
                "genres":[{"name":"Adventure"},{"name":"Fantasy"}],
                "themes":[{"name":"Shounen"}],
                "relations":[
                  {
                    "relation":"Sequel",
                    "entry":[{
                      "mal_id":59978,
                      "type":"anime",
                      "media_type":"TV",
                      "name":"Sousou no Frieren 2nd Season",
                      "url":"https://mal/anime/59978",
                      "images":{"webp":{"large_image_url":"https://img/sequel.webp"}}
                    }]
                  }
                ],
                "theme":{"openings":["OP 1"],"endings":["ED 1"]},
                "external":[{"name":"Official Site","url":"https://frieren.jp"}],
                "streaming":[{"name":"Crunchyroll","url":"https://stream/frieren"}]
              }
            }
            """.trimIndent(),
        )

        assertThat(overview.malId).isEqualTo(52991)
        assertThat(overview.title).isEqualTo("Sousou no Frieren")
        assertThat(overview.largePosterUrl).isEqualTo("https://img/poster-large.webp")
        assertThat(overview.score).isEqualTo(9.31)
        assertThat(overview.studios.single().name).isEqualTo("Madhouse")
        assertThat(overview.genres).containsAtLeast("Adventure", "Fantasy", "Shounen")
        assertThat(overview.relations.single().mediaType).isEqualTo("TV")
        assertThat(overview.relations.single().imageUrl).isEqualTo("https://img/sequel.webp")
        assertThat(overview.openings).containsExactly("OP 1")
        assertThat(overview.streamingLinks.single().name).isEqualTo("Crunchyroll")
        assertThat(overview.trailer?.youtubeId).isEqualTo("abc123")
    }

    @Test
    fun episodesParser_mapsPaginationAndFlags() {
        val page = parseTenraiEpisodesPage(
            gson,
            """
            {
              "pagination":{"last_visible_page":3,"has_next_page":true,"current_page":1},
              "data":[
                {
                  "mal_id":1,
                  "url":"https://mal/episode/1",
                  "title":"The Journey's End",
                  "title_japanese":"冒険の終わり",
                  "title_romanji":"Bouken no Owari",
                  "aired":"2023-09-29T00:00:00+00:00",
                  "score":4.8,
                  "filler":false,
                  "recap":false
                }
              ]
            }
            """.trimIndent(),
        )

        assertThat(page.page).isEqualTo(1)
        assertThat(page.lastPage).isEqualTo(3)
        assertThat(page.hasNextPage).isTrue()
        assertThat(page.items.single().number).isEqualTo(1)
        assertThat(page.items.single().filler).isFalse()
        assertThat(page.items.single().score).isEqualTo(4.8)
    }

    @Test
    fun staffRecommendationsAndStatistics_areDefensive() {
        val staff = parseTenraiStaff(
            gson,
            """{"data":[{"person":{"mal_id":1,"name":"Director","images":{"jpg":{"image_url":"https://img/person.jpg"}}},"positions":["Director","Storyboard"]}]}""",
        )
        val recommendations = parseTenraiRecommendations(
            gson,
            """{"data":[{"entry":{"mal_id":2,"title":"Recommended","images":{"jpg":{"large_image_url":"https://img/rec.jpg"}}},"votes":42}]}""",
        )
        val statistics = parseTenraiStatistics(
            gson,
            """{"data":{"watching":10,"completed":20,"on_hold":3,"dropped":4,"plan_to_watch":30,"total":67}}""",
        )

        assertThat(staff.single().positions).containsExactly("Director", "Storyboard")
        assertThat(recommendations.single().votes).isEqualTo(42)
        assertThat(statistics?.total).isEqualTo(67)
    }

    @Test
    fun catalogParser_handlesScheduleAndSeasonShape() {
        val page = parseTenraiCatalogPage(
            gson,
            """
            {
              "pagination":{"last_visible_page":2,"has_next_page":true,"current_page":1},
              "data":[{
                "mal_id":123,
                "title":"Example",
                "title_english":"Example EN",
                "images":{"webp":{"large_image_url":"https://img/example.webp"}},
                "type":"TV",
                "episodes":12,
                "status":"Currently Airing",
                "score":8.1,
                "year":2026,
                "season":"fall",
                "broadcast":{"string":"Mondays at 20:00 (JST)"}
              }]
            }
            """.trimIndent(),
        )

        assertThat(page.items.single().malId).isEqualTo(123)
        assertThat(page.items.single().broadcast).contains("Mondays")
        assertThat(page.hasNextPage).isTrue()
    }
}
