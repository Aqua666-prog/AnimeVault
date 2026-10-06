package com.sergey.animevault.data.transport

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class MediaTransportSourceTest {
    @Test
    fun signedUrlRotationKeepsLogicalRefreshIdentity() {
        val old = stream("https://cdn.example/video.m3u8?token=old&expires=1")
            .toMediaTransportSource("provider", "Provider")
        val fresh = stream("https://cdn.example/video.m3u8?token=new&expires=2")
            .toMediaTransportSource("provider", "Provider")

        assertThat(old.key).isNotEqualTo(fresh.key)
        assertThat(old.stableRefreshIdentity).isEqualTo(fresh.stableRefreshIdentity)
    }

    @Test
    fun refreshResolverSelectsFreshSignedReplacement() {
        val current = stream("https://cdn.example/video.m3u8?token=old")
            .toMediaTransportSource("provider", "Provider")
        val wrongVoice = stream("https://cdn.example/other.m3u8?token=new")
            .copy(translation = "Other", translationId = "other")
            .toMediaTransportSource("provider", "Provider")
        val replacement = stream("https://cdn.example/video.m3u8?token=new")
            .toMediaTransportSource("provider", "Provider")

        assertThat(
            TransportRefreshResolver.selectReplacement(current, listOf(wrongVoice, replacement)),
        ).isEqualTo(replacement)
    }

    @Test
    fun mergeDropsStoredStaleUrlWhenFreshLogicalSourceExists() {
        val stored = stream("https://cdn.example/video.m3u8?token=old")
            .toMediaTransportSource("provider", "Provider")
        val fresh = stream("https://cdn.example/video.m3u8?token=new")
            .toMediaTransportSource("provider", "Provider")

        val merged = TransportRefreshResolver.mergeFreshAndStored(
            fresh = listOf(fresh),
            stored = listOf(stored),
        )

        assertThat(merged).containsExactly(fresh)
    }

    @Test
    fun networkFallbackPrefersSiblingRouteBeforeQualityDropOnSameCdn() {
        val current = source("current", "cdn-a.example", 1080)
        val sameCdnLower = source("lower", "cdn-a.example", 720)
        val siblingSameQuality = source("mirror", "cdn-b.example", 1080)

        val selected = TransportCandidatePlanner.selectFallback(
            sources = listOf(current, sameCdnLower, siblingSameQuality),
            current = current,
            failedKeys = setOf(current.key),
            failureKind = TransportFailureKind.NETWORK,
        )

        assertThat(selected).isEqualTo(siblingSameQuality)
    }

    @Test
    fun playbackAndDownloadPoliciesCanShareStateMachineWithDifferentCooldownRules() {
        val source = source("route", "cdn.example", 720)
        val playback = TransportRouteHealthTracker(PlaybackTransportRoutePolicy)
        val download = TransportRouteHealthTracker(DownloadTransportRoutePolicy)

        playback.recordFailure(source, TransportFailureKind.DNS, nowMs = 1_000L)
        download.recordFailure(source, TransportFailureKind.DNS, nowMs = 1_000L)

        assertThat(playback.shouldAttempt(source, nowMs = 1_001L)).isFalse()
        assertThat(download.shouldAttempt(source, nowMs = 1_001L)).isTrue()

        download.recordFailure(source, TransportFailureKind.DNS, nowMs = 2_000L)
        assertThat(download.shouldAttempt(source, nowMs = 2_001L)).isFalse()
    }

    private fun stream(url: String) = OnlineStream(
        id = "stream",
        quality = 720,
        url = url,
        type = OnlineStreamType.HLS,
        translation = "Voice",
        sourceName = "CDN",
        translationId = "voice",
        providerId = "provider",
        providerName = "Provider",
        refreshable = true,
    )

    private fun source(key: String, route: String, quality: Int) = MediaTransportSource(
        key = key,
        uri = "https://$route/$key.m3u8",
        kind = TransportKind.HLS,
        providerId = "provider",
        translation = "Voice",
        translationKey = "voice",
        quality = quality,
        routeFamily = route,
    )
}
