package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class OnlineStreamResolverTest {
    @Test
    fun preferredVoiceWinsOverHigherQualityOfAnotherVoice() {
        val other1080 = stream("other", 1080, "Other", "Kodik")
        val preferred720 = stream("preferred", 720, "AniLibria", "Kodik")

        val result = OnlineStreamResolver.selectPreferred(
            listOf(other1080, preferred720),
            OnlineStreamPreference(translation = "anilibria", quality = 1080, sourceName = "Kodik"),
        )

        assertThat(result).isEqualTo(preferred720)
    }

    @Test
    fun fallbackStaysOnSameVoiceAndChoosesClosestQuality() {
        val current = stream("current", 1080, "Voice", "Kodik")
        val same720 = stream("same720", 720, "Voice", "Kodik")
        val same480 = stream("same480", 480, "Voice", "Kodik")
        val other1080 = stream("other1080", 1080, "Other", "Kodik")

        val result = OnlineStreamResolver.selectFallback(
            streams = listOf(current, same480, other1080, same720),
            current = current,
            failedStreamKeys = setOf(OnlineStreamResolver.failureKey(current)),
        )

        assertThat(result).isEqualTo(same720)
    }

    private fun stream(id: String, quality: Int, voice: String, source: String) = OnlineStream(
        id = id,
        quality = quality,
        url = "https://example.test/$id.m3u8",
        type = OnlineStreamType.HLS,
        translation = voice,
        sourceName = source,
    )
}
