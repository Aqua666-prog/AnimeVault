package com.sergey.animevault.data.jutsu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JutSuParserTest {
    @Test
    fun details_extractTitlePosterAndSeasonedEpisodes() {
        val html = """
            <div id="dle-content">
              <h1 class="header_video">Смотреть Волчица и пряности все серии и сезоны</h1>
              <div class="all_anime_title" style="background-image: url('/uploads/spice.jpg')"></div>
              <p>История торговца и мудрой волчицы.</p>
              <a class="video" href="/ookami-to-koshinryou/season-1/episode-1.html">1</a>
              <a class="video" href="/ookami-to-koshinryou/season-2/episode-1.html">1</a>
            </div>
        """.trimIndent()

        val details = parseJutSuDetails("ookami-to-koshinryou", html)

        assertThat(details.title).isEqualTo("Волчица и пряности")
        assertThat(details.posterUrl).isEqualTo("https://jut.su/uploads/spice.jpg")
        assertThat(details.episodes).hasSize(2)
        assertThat(details.episodes.map { it.name }).containsExactly("Сезон 1", "Сезон 2").inOrder()
    }

    @Test
    fun streams_onlyUseSourcesInsideMyPlayer() {
        val html = """
            <video id="advertising">
              <source src="https://cdn.example/ad.mp4" res="1080">
            </video>
            <video class="video-js" id="my-player">
              <source src="https://r1.jut.su/video-360.mp4" res="360">
              <source src="//r2.jut.su/video-720.mp4" label="720p">
            </video>
            <source src="https://cdn.example/tracker.mp4" res="2160">
        """.trimIndent()

        val streams = parseJutSuStreams(
            document = html,
            pageUrl = "https://jut.su/test/episode-1.html",
        )

        assertThat(streams.map { it.quality }).containsExactly(720, 360).inOrder()
        assertThat(streams.map { it.url }).containsExactly(
            "https://r2.jut.su/video-720.mp4",
            "https://r1.jut.su/video-360.mp4",
        ).inOrder()
        assertThat(streams.map { it.headers["Referer"] }).containsExactly(
            "https://jut.su/test/episode-1.html",
            "https://jut.su/test/episode-1.html",
        )
    }

    @Test
    fun streams_returnEmptyWhenExpectedPlayerIsMissing() {
        val html = "<video id=\"preview\"><source src=\"https://cdn.example/preview.mp4\"></video>"

        assertThat(parseJutSuStreams(html, "https://jut.su/test/episode-1.html")).isEmpty()
    }

    @Test
    fun streams_ignorePixelPlaceholderThatWouldFinishImmediately() {
        val html = """
            <video id="my-player">
              <source src="https://gen.jut.su/templates/school/images/pixel.png?480" res="480">
            </video>
        """.trimIndent()

        assertThat(parseJutSuStreams(html, "https://jut.su/test/episode-1.html")).isEmpty()
    }
}
