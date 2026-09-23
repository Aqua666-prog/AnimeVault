package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class OnlineFallbackTest {
    @Test
    fun `fallback prefers same dubbing and native stream`() {
        val current = stream("a", 1080, "AniDUB", OnlineStreamType.HLS)
        val sameVoice = stream("b", 720, "AniDUB", OnlineStreamType.HLS)
        val otherVoice = stream("c", 1080, "Studio Band", OnlineStreamType.HLS)
        val embedSameVoice = stream("d", null, "AniDUB", OnlineStreamType.EMBED)

        val fallback = selectFallbackStream(
            streams = listOf(current, otherVoice, embedSameVoice, sameVoice),
            current = current,
            failedStreamKeys = setOf(current.failureKey()),
        )

        assertThat(fallback).isEqualTo(sameVoice)
    }

    @Test
    fun `fallback skips every failed stream`() {
        val current = stream("a", 1080, "AniDUB", OnlineStreamType.HLS)
        val backup = stream("b", 720, "AniDUB", OnlineStreamType.HLS)

        val fallback = selectFallbackStream(
            streams = listOf(current, backup),
            current = current,
            failedStreamKeys = setOf(current.failureKey(), backup.failureKey()),
        )

        assertThat(fallback).isNull()
    }

    @Test
    fun `fallback does not retry the same failed url under another id`() {
        val current = stream("a", 1080, "AniDUB", OnlineStreamType.HLS, "https://cdn.example/video.m3u8")
        val duplicate = stream("b", 720, "AniDUB", OnlineStreamType.HLS, "https://cdn.example/video.m3u8")
        val backup = stream("c", 720, "AniDUB", OnlineStreamType.HLS, "https://cdn.example/backup.m3u8")

        val fallback = selectFallbackStream(
            streams = listOf(current, duplicate, backup),
            current = current,
            failedStreamKeys = setOf(current.failureKey()),
        )

        assertThat(fallback).isEqualTo(backup)
    }

    private fun stream(
        id: String,
        quality: Int?,
        voice: String,
        type: OnlineStreamType,
        url: String = "https://example/$id",
    ) = OnlineStream(
        id = id,
        quality = quality,
        url = url,
        type = type,
        translation = voice,
        sourceName = "Test",
    )
}
