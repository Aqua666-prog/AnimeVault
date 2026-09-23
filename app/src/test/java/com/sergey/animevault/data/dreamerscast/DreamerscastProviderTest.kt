package com.sergey.animevault.data.dreamerscast

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class DreamerscastProviderTest {
    @Test
    fun parseSearch_readsCurrentAjaxShape() {
        val page = parseDreamerscastSearch(
            json = """
                {
                  "releases": [
                    {
                      "russian": "История электричества в двадцатом веке",
                      "original": "Nijuuseiki Denki Mokuroku",
                      "image": "//cache.dreamerscast.com/poster.webp",
                      "dateissue": 2026,
                      "url": "/home/release/587-nijuuseiki-denki-mokuroku"
                    }
                  ],
                  "pageCount": 4
                }
            """.trimIndent(),
            currentPage = 1,
            requestedLimit = 24,
        )

        assertThat(page.totalPages).isEqualTo(4)
        assertThat(page.items).hasSize(1)
        assertThat(page.items.single().year).isEqualTo(2026)
        assertThat(page.items.single().uri).isEqualTo("/home/release/587-nijuuseiki-denki-mokuroku")
        assertThat(page.items.single().toCard().posterUrl).isEqualTo("https://cache.dreamerscast.com/poster.webp")
    }

    @Test
    fun extractPlayerJson_decodesPlayerJsPayload() {
        val json = """{"file":[{"title":"1 серия","file":"[1080p]https://cdn.example/hls/e1/master.m3u8"}]}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val document = """<script>Playerjs("#2$encoded");</script>"""

        assertThat(extractDreamerscastPlayerJson(document)).isEqualTo(json)
    }

    @Test
    fun parseEpisodes_preservesQualitiesAndTranslation() {
        val json = """
            {
              "file": [
                {
                  "title": "Серия 12",
                  "file": "[1080p]https://cdn.example/hls/e12/1080.m3u8,[720p]https://cdn.example/hls/e12/720.m3u8"
                }
              ]
            }
        """.trimIndent()

        val episode = parseDreamerscastEpisodes("/home/release/test", json).single()

        assertThat(episode.ordinal).isEqualTo(12.0)
        assertThat(episode.streams.map { it.quality }).containsExactly(1080, 720).inOrder()
        assertThat(episode.streams.map { it.translation }.distinct()).containsExactly("Dream Cast")
        assertThat(episode.streams.all { it.url.endsWith(".m3u8") }).isTrue()
    }
}
