package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class OnlinePreferredStreamTest {
    @Test
    fun keepsOriginalOrderWhenNoPreferenceExists() {
        val first = stream("first", 480, "AniLibria", "Kodik", OnlineStreamType.HLS)
        val second = stream("second", 1080, "AniDUB", "Other", OnlineStreamType.HLS)

        val selected = selectPreferredOnlineStream(
            streams = listOf(first, second),
            translation = null,
            quality = null,
            sourceName = null,
        )

        assertThat(selected).isEqualTo(first)
    }

    @Test
    fun translationPreferenceWinsAcrossEpisodes() {
        val dub = stream("dub", 1080, "AniDUB", "Kodik", OnlineStreamType.HLS)
        val libria = stream("libria", 720, "AniLibria", "Kodik", OnlineStreamType.HLS)

        val selected = selectPreferredOnlineStream(
            streams = listOf(dub, libria),
            translation = "anilibria",
            quality = 1080,
            sourceName = "Kodik",
        )

        assertThat(selected).isEqualTo(libria)
    }

    @Test
    fun qualityAndNativePlaybackBreakTies() {
        val embed720 = stream("embed", 720, "Voice", "Source", OnlineStreamType.EMBED)
        val hls720 = stream("hls", 720, "Voice", "Source", OnlineStreamType.HLS)
        val hls480 = stream("low", 480, "Voice", "Source", OnlineStreamType.HLS)

        val selected = selectPreferredOnlineStream(
            streams = listOf(embed720, hls480, hls720),
            translation = "Voice",
            quality = 720,
            sourceName = "Source",
        )

        assertThat(selected).isEqualTo(hls720)
    }

    private fun stream(
        id: String,
        quality: Int,
        translation: String,
        source: String,
        type: OnlineStreamType,
    ) = OnlineStream(
        id = id,
        quality = quality,
        url = "https://example.test/$id",
        type = type,
        translation = translation,
        sourceName = source,
    )
}
