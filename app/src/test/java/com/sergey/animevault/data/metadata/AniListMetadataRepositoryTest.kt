package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AniListMetadataRepositoryTest {
    @Test
    fun parseSearchResponse_mapsLowercasePageAliasAndMetadata() {
        val json = """
            {
              "data": {
                "page": {
                  "media": [
                    {
                      "id": 162893,
                      "idMal": 54492,
                      "title": {
                        "romaji": "Kusuriya no Hitorigoto",
                        "english": "The Apothecary Diaries",
                        "native": "薬屋のひとりごと"
                      },
                      "synonyms": ["The Pharmacist's Monologue"],
                      "coverImage": {
                        "extraLarge": "https://example.invalid/poster.jpg",
                        "large": "https://example.invalid/poster-small.jpg",
                        "color": "#9f8762"
                      },
                      "bannerImage": "https://example.invalid/banner.jpg",
                      "description": "**Маомао**<br>Императорский двор",
                      "seasonYear": 2023,
                      "episodes": 24,
                      "format": "TV",
                      "status": "FINISHED",
                      "genres": ["Drama", "Mystery"],
                      "averageScore": 88,
                      "siteUrl": "https://anilist.co/anime/162893"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val candidate = parseAniListSearchResponse(Gson(), json).single()

        assertThat(candidate.anilistId).isEqualTo(162893)
        assertThat(candidate.malId).isEqualTo(54492)
        assertThat(candidate.canonicalTitle).isEqualTo("Kusuriya no Hitorigoto")
        assertThat(candidate.englishTitle).isEqualTo("The Apothecary Diaries")
        assertThat(candidate.posterUrl).isEqualTo("https://example.invalid/poster.jpg")
        assertThat(candidate.year).isEqualTo(2023)
        assertThat(candidate.episodeCount).isEqualTo(24)
        assertThat(candidate.genres).containsExactly("Drama", "Mystery").inOrder()
        assertThat(candidate.description).isEqualTo("Маомао\nИмператорский двор")
    }

    @Test
    fun parseSearchResponse_surfacesGraphQlError() {
        val error = runCatching {
            parseAniListSearchResponse(
                Gson(),
                """{"errors":[{"message":"Rate limited"}]}""",
            )
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("Rate limited")
    }


    @Test
    fun parseMalIdResponse_mapsDirectMediaAlias() {
        val json = """
            {
              "data": {
                "media": {
                  "id": 162893,
                  "idMal": 54492,
                  "title": {"romaji": "Kusuriya no Hitorigoto"},
                  "synonyms": [],
                  "coverImage": {"large": "https://example.invalid/poster.jpg"},
                  "seasonYear": 2023,
                  "episodes": 24,
                  "format": "TV",
                  "status": "FINISHED",
                  "genres": ["Drama"],
                  "averageScore": 88
                }
              }
            }
        """.trimIndent()

        val candidate = parseAniListMalIdResponse(Gson(), json)

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.anilistId).isEqualTo(162893)
        assertThat(candidate.malId).isEqualTo(54492)
        assertThat(candidate.canonicalTitle).isEqualTo("Kusuriya no Hitorigoto")
    }

    @Test
    fun parseMalIdResponse_returnsNullWhenMediaMissing() {
        assertThat(parseAniListMalIdResponse(Gson(), """{"data":{"media":null}}""")).isNull()
    }

    @Test
    fun sanitizeDescription_removesSimpleMarkdownAndCompressesBlankLines() {
        val result = sanitizeAniListDescription(
            "**Аптекарь** [Маомао](https://example.invalid)~~!~~<br><br/><br />Дворец",
        )

        assertThat(result).isEqualTo("Аптекарь Маомао!\n\nДворец")
    }

    @Test
    fun sanitizeDescription_returnsNullForBlankInput() {
        assertThat(sanitizeAniListDescription("  \n\n ")).isNull()
        assertThat(sanitizeAniListDescription(null)).isNull()
    }
}
