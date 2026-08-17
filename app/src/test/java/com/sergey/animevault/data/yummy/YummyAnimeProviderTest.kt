package com.sergey.animevault.data.yummy

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class YummyAnimeProviderTest {
    @Test
    fun `groups several dubbings into one episode`() {
        val episodes = mergeYummyEpisodes(
            animeId = 42,
            videos = listOf(
                YummyVideoDto(
                    videoId = 1,
                    number = "1",
                    iframeUrl = "//kodik.info/video/abc",
                    data = YummyVideoDataDto(player = "Kodik", dubbing = "AniDUB"),
                ),
                YummyVideoDto(
                    videoId = 2,
                    number = "1",
                    iframeUrl = "https://player.example/embed/2",
                    data = YummyVideoDataDto(player = "CVH", dubbing = "Studio Band"),
                ),
                YummyVideoDto(
                    videoId = 3,
                    number = "2",
                    iframeUrl = "https://cdn.example/2.m3u8",
                    data = YummyVideoDataDto(player = "Direct", dubbing = "AniDUB"),
                ),
            ),
        )

        assertThat(episodes).hasSize(2)
        assertThat(episodes.first().streams).hasSize(2)
        assertThat(episodes.first().streams.mapNotNull { it.translation })
            .containsExactly("AniDUB", "Studio Band")
        assertThat(episodes[1].streams.single().type).isEqualTo(OnlineStreamType.HLS)
    }

    @Test
    fun `normalizes protocol relative iframe`() {
        val episode = mergeYummyEpisodes(
            7,
            listOf(YummyVideoDto(videoId = 8, number = "1", iframeUrl = "//kodik.info/x")),
        ).single()

        assertThat(episode.streams.single().url).isEqualTo("https://kodik.info/x")
    }
}
