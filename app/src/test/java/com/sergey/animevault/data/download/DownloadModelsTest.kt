package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class DownloadModelsTest {
    @Test
    fun chooseDownloadStream_ignoresEmbedAndPrefersExactQuality() {
        val streams = listOf(
            stream("embed", null, OnlineStreamType.EMBED),
            stream("720", 720, OnlineStreamType.HLS),
            stream("1080", 1080, OnlineStreamType.HLS),
        )
        assertThat(chooseDownloadStream(streams, null, 720)?.id).isEqualTo("720")
    }

    @Test
    fun chooseDownloadStream_prefersVoiceBeforeQuality() {
        val preferred = stream("voice720", 720, OnlineStreamType.HLS, translation = "AniDub")
        val other = stream("other1080", 1080, OnlineStreamType.HLS, translation = "Other")
        val key = preferred.translationPreferenceKey
        assertThat(chooseDownloadStream(listOf(other, preferred), key, 1080)?.id).isEqualTo("voice720")
    }

    @Test
    fun chooseDownloadStream_returnsNullForEmbedOnly() {
        assertThat(chooseDownloadStream(listOf(stream("embed", null, OnlineStreamType.EMBED)), null, null)).isNull()
    }

    @Test
    fun downloadId_doesNotChangeWhenSignedUrlRotates() {
        val first = stream("same", 1080, OnlineStreamType.HLS).copy(
            url = "https://cdn.example/video.m3u8?token=one",
        )
        val second = first.copy(url = "https://cdn.example/video.m3u8?token=two")
        assertThat(downloadId("provider", "release", "episode", first))
            .isEqualTo(downloadId("provider", "release", "episode", second))
    }

    @Test
    fun downloadCacheKey_survivesSignedUrlRotation() {
        val first = "https://cdn.example/video/segment-01.ts?token=one&part=1"
        val second = "https://cdn.example/video/segment-01.ts?token=two&part=1"

        assertThat(downloadCacheKey("download", first))
            .isEqualTo(downloadCacheKey("download", second))
    }

    @Test
    fun downloadCacheKey_keepsDifferentHostsSeparate() {
        val video = "https://video-cdn.example/segment-01.ts?token=one"
        val audio = "https://audio-cdn.example/segment-01.ts?token=one"

        assertThat(downloadCacheKey("download", video))
            .isNotEqualTo(downloadCacheKey("download", audio))
    }

    @Test
    fun downloadCacheKey_keepsResourceAndDownloadNamespacesSeparate() {
        val firstSegment = "https://cdn.example/video.ts?part=1&signature=old"
        val secondSegment = "https://cdn.example/video.ts?part=2&signature=new"

        assertThat(downloadCacheKey("download", firstSegment))
            .isNotEqualTo(downloadCacheKey("download", secondSegment))
        assertThat(downloadCacheKey("other-download", firstSegment))
            .isNotEqualTo(downloadCacheKey("download", firstSegment))
    }

    private fun stream(
        id: String,
        quality: Int?,
        type: OnlineStreamType,
        translation: String? = null,
    ) = OnlineStream(
        id = id,
        quality = quality,
        url = "https://example.com/$id",
        type = type,
        translation = translation,
        sourceName = "test",
    )
}
