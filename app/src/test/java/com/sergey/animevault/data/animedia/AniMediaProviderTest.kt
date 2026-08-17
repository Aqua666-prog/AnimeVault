package com.sergey.animevault.data.animedia

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AniMediaProviderTest {
    @Test
    fun `catalog parser reads DLE poster cards and pagination`() {
        val html = """
            <img src="/uploads/posts/test.webp">
            <a href="https://amd.online/123-test-anime.html" class="poster__link"><h3 class="poster__title line-clamp">Тестовое аниме (2026)</h3></a>
            <div>4 из 12</div>
            <a href="/page/2/">2</a><a href="/page/137/">137</a>
        """.trimIndent()

        val page = parseAniMediaCatalog(html)

        assertThat(page.totalPages).isEqualTo(137)
        assertThat(page.items).hasSize(1)
        assertThat(page.items.single().path).isEqualTo("/123-test-anime.html")
        assertThat(page.items.single().name).isEqualTo("Тестовое аниме (2026)")
        assertThat(page.items.single().posterUrl).isEqualTo("/uploads/posts/test.webp")
        assertThat(page.items.single().isOngoing).isTrue()
    }

    @Test
    fun `release episode parser keeps public vod reference`() {
        val html = """
            <div class="pmovie__main-info ws-nowrap">Season 2</div>
            <button data-vid="1" data-vlnk="https://video.example/vod/abc">1</button>
            <button data-vid="2" data-vlnk="https://video.example/vod/def">2</button>
        """.trimIndent()

        val episodes = parseAniMediaEpisodes("/release.html", html, season = 2)

        assertThat(episodes.map { it.ordinal }).containsExactly(1.0, 2.0).inOrder()
        assertThat(episodes.first().sourceRef).isEqualTo("https://video.example/vod/abc")
        assertThat(episodes.first().sortOrder).isEqualTo(20_001.0)
    }

    @Test
    fun `vod parser extracts player hls`() {
        val html = """Playerjs({file: "https://cdn.example/master.m3u8"});"""
        assertThat(parseAniMediaVodHls(html)).isEqualTo("https://cdn.example/master.m3u8")
    }
}
