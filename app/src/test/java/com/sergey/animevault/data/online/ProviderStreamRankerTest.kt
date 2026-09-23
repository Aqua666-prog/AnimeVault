package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderStreamRankerTest {
    @Test
    fun `healthy native stream outranks unhealthy embed`() {
        val healthy = ProviderHealthState(
            providerId = "a",
            status = ProviderHealthStatus.AVAILABLE,
            latencyMs = 100,
            successfulRequests = 10,
        )
        val weak = ProviderHealthState(
            providerId = "b",
            status = ProviderHealthStatus.DEGRADED,
            latencyMs = 3_000,
            successfulRequests = 2,
            failedRequests = 6,
            consecutiveFailures = 2,
        )
        val hls = stream("a", OnlineStreamType.HLS, 720)
        val embed = stream("b", OnlineStreamType.EMBED, 1080)

        assertThat(ProviderStreamRanker.score(hls, healthy))
            .isGreaterThan(ProviderStreamRanker.score(embed, weak))
    }

    @Test
    fun `quality breaks otherwise similar native streams`() {
        val state = ProviderHealthState(providerId = "a")
        assertThat(ProviderStreamRanker.score(stream("a", OnlineStreamType.HLS, 1080), state))
            .isGreaterThan(ProviderStreamRanker.score(stream("a", OnlineStreamType.HLS, 480), state))
    }

    @Test
    fun `provider priority breaks close ties`() {
        val state = ProviderHealthState(providerId = "a")
        val stream = stream("a", OnlineStreamType.HLS, 720)
        assertThat(ProviderStreamRanker.score(stream, state, providerPriority = 100))
            .isGreaterThan(ProviderStreamRanker.score(stream, state, providerPriority = 0))
    }

    @Test
    fun `download score uses download channel instead of catalogue health`() {
        val weakDownload = ProviderHealthState(
            providerId = "a",
            channels = mapOf(
                ProviderHealthChannel.CATALOG to ProviderChannelHealthState(
                    status = ProviderHealthStatus.AVAILABLE,
                    successfulRequests = 20,
                    latencyMs = 50,
                    lastSuccessAt = System.currentTimeMillis(),
                ),
                ProviderHealthChannel.DOWNLOAD to ProviderChannelHealthState(
                    status = ProviderHealthStatus.UNAVAILABLE,
                    failedRequests = 4,
                    consecutiveFailures = 3,
                    cooldownUntilMs = System.currentTimeMillis() + 60_000L,
                ),
            ),
        )
        val healthyDownload = ProviderHealthState(
            providerId = "b",
            channels = mapOf(
                ProviderHealthChannel.CATALOG to ProviderChannelHealthState(
                    status = ProviderHealthStatus.DEGRADED,
                    failedRequests = 2,
                ),
                ProviderHealthChannel.DOWNLOAD to ProviderChannelHealthState(
                    status = ProviderHealthStatus.AVAILABLE,
                    successfulRequests = 10,
                    latencyMs = 150,
                    lastSuccessAt = System.currentTimeMillis(),
                ),
            ),
        )

        assertThat(ProviderStreamRanker.downloadScore(stream("b", OnlineStreamType.HLS, 720), healthyDownload))
            .isGreaterThan(ProviderStreamRanker.downloadScore(stream("a", OnlineStreamType.HLS, 720), weakDownload))
    }

    private fun stream(provider: String, type: OnlineStreamType, quality: Int) = OnlineStream(
        id = "$provider-$quality",
        quality = quality,
        url = "https://example.test/$provider/$quality",
        type = type,
        providerId = provider,
    )
}
