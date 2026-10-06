package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSourceExhaustionTest {
    @Test
    fun failedAndCoolingRoutesCanTemporarilyExhaustEpisode() {
        val failed = variant("a", "cdn-a.example")
        val cooling = variant("b", "cdn-b.example")

        val report = PlaybackSourceExhaustion.inspect(
            variants = listOf(failed, cooling),
            failedVariantKeys = setOf(failed.key),
            blockedHostFamilies = setOf("cdn-b.example"),
        )

        assertThat(report.exhausted).isTrue()
        assertThat(report.temporarilyExhausted).isTrue()
        assertThat(report.remainingVariants).isEqualTo(0)
    }

    @Test
    fun localVariantRemainsAvailableEvenWhenAllOnlineHostsCoolDown() {
        val online = variant("online", "cdn-a.example")
        val local = PlaybackVariant(
            key = "local",
            episodeKey = "episode:1",
            uri = "content://episode/1",
            kind = PlaybackVariantKind.LOCAL,
        )

        val report = PlaybackSourceExhaustion.inspect(
            variants = listOf(online, local),
            failedVariantKeys = emptySet(),
            blockedHostFamilies = setOf("cdn-a.example"),
        )

        assertThat(report.exhausted).isFalse()
        assertThat(report.remainingVariants).isEqualTo(1)
    }

    private fun variant(key: String, host: String) = PlaybackVariant(
        key = key,
        episodeKey = "episode:1",
        uri = "https://$host/$key.m3u8",
        kind = PlaybackVariantKind.HLS,
        providerId = "provider",
        hostFamily = host,
    )
}
