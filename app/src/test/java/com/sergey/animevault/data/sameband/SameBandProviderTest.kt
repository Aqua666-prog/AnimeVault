package com.sergey.animevault.data.sameband

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SameBandProviderTest {
    @Test
    fun `catalog parser reads public card metadata`() {
        val html = """
            <div class="col-auto">
              <a class="image" href="/anime/20-test.html">
                <img class="swiper-lazy" src="/uploads/test.webp">
                <div class="poster" title="Тестовое аниме 2026"></div>
              </a>
            </div>
        """.trimIndent()

        val cards = parseSameBandCatalog(html, ongoing = true)

        assertThat(cards).hasSize(1)
        assertThat(cards.single().path).isEqualTo("/anime/20-test.html")
        assertThat(cards.single().name).isEqualTo("Тестовое аниме 2026")
        assertThat(cards.single().posterUrl).isEqualTo("/uploads/test.webp")
        assertThat(cards.single().isOngoing).isTrue()
    }

    @Test
    fun `player parser finds iframe and playlist`() {
        val release = """
            <div class="player"><div class="player-content"><iframe src="/pl/a/Test.html"></iframe></div></div>
        """.trimIndent()
        val player = """<script>var player = new Playerjs({id:"player",file:"/v/list/Test Season.txt"});</script>"""

        assertThat(parseSameBandPlayerUrl(release)).isEqualTo("/pl/a/Test.html")
        assertThat(parseSameBandPlaylistUrl(player)).isEqualTo("/v/list/Test_Season.txt")
    }

    @Test
    fun `playlist parser exposes quality tagged hls streams`() {
        val json = """
          [
            {
              "title": "<div class='playlist_duration'>23:37</div> Тест 01",
              "file": "[480p]/v/anime/test-01-480.m3u8,[720p]/v/anime/test-01-720.m3u8,[1080p]/v/anime/test-01-1080.m3u8",
              "thumbnails": "/v/anime/test-01.txt"
            }
          ]
        """.trimIndent()

        val episodes = parseSameBandPlaylist(json, "/anime/test.html")

        assertThat(episodes).hasSize(1)
        assertThat(episodes.single().ordinal).isEqualTo(1.0)
        assertThat(episodes.single().streams.map { it.quality }).containsExactly(1080, 720, 480).inOrder()
        assertThat(episodes.single().streams.first().url).isEqualTo("https://sameband.studio/v/anime/test-01-1080.m3u8")
    }
}
