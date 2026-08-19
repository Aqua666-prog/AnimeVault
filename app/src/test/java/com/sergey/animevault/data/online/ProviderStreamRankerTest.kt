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

    private fun stream(provider: String, type: OnlineStreamType, quality: Int) = OnlineStream(
        id = "$provider-$quality",
        quality = quality,
        url = "https://example.test/$provider/$quality",
        type = type,
        providerId = provider,
    )
}
