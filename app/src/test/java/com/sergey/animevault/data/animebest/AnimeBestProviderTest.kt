package com.sergey.animevault.data.animebest

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnimeBestProviderTest {
    @Test
    fun `search parser reads card season year and poster`() {
        val html = """
            <div class="shortstory-listab">
              <img class="img-fit lozad" data-src="/uploads/test.webp">
              <div class="shortstory-listab-title"><a href="https://b1.animebesst.org/test-season-2.html">Тестовое аниме 2 сезон</a></div>
              <a href="/2026/">2026</a>
            </div>
            <div id="sidebar"></div>
        """.trimIndent()

        val items = parseAnimeBestSearch(html)

        assertThat(items).hasSize(1)
        assertThat(items.single().url).isEqualTo("https://b1.animebesst.org/test-season-2.html")
        assertThat(items.single().name).isEqualTo("Тестовое аниме 2 сезон")
        assertThat(items.single().season).isEqualTo(2)
        assertThat(items.single().year).isEqualTo(2026)
        assertThat(items.single().posterUrl).isEqualTo("/uploads/test.webp")
    }

    @Test
    fun `video list parser preserves multiple voices for one episode`() {
        val html = """
            <script>
              var videoList = [{"id":"1 (AniDUB)","link":"\/\/player.example\/e1"},{"id":"1 (Studio Band)","link":"\/\/player2.example\/e1"}];
            </script>
        """.trimIndent()

        val entries = parseAnimeBestVideoList(html)

        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.episode }).containsExactly(1.0, 1.0)
        assertThat(entries.map { it.voice }).containsExactly("AniDUB", "Studio Band")
        assertThat(entries.first().embedUrl).isEqualTo("https://player.example/e1")
    }

    @Test
    fun `release groups voices into a single episode`() {
        val html = """
            <html><head><meta property="og:title" content="Тестовое аниме"></head><body>
            <h1>Тестовое аниме</h1>
            <script>var videoList = [{"id":"1 (AniDUB)","link":"\/\/player.example\/e1"},{"id":"1 (Studio Band)","link":"\/\/player2.example\/e1"}];</script>
            </body></html>
        """.trimIndent()

        val release = parseAnimeBestRelease("https://b1.animebesst.org/test.html", html)

        assertThat(release.episodes).hasSize(1)
        assertThat(release.episodes.single().streams).hasSize(2)
        assertThat(release.episodes.single().streams.map { it.translation }).containsExactly("AniDUB", "Studio Band")
    }

    @Test
    fun `iframe parser extracts hls`() {
        val iframe = """Playerjs({file:"https://cdn.example/master.m3u8?token=abc"})"""
        assertThat(parseAnimeBestHls(iframe)).isEqualTo("https://cdn.example/master.m3u8?token=abc")
    }
}
