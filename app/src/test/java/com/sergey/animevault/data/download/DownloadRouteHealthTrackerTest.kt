package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadRouteHealthTrackerTest {
    @Test
    fun deadRegionalRouteDoesNotDisableSiblingCdn() {
        val tracker = DownloadRouteHealthTracker()
        val regional = source("https://cache-rfn.libria.fun/video/playlist.m3u8")
        val global = source("https://cache.libria.fun/video/playlist.m3u8")
        val timeout = DownloadFailure(
            kind = DownloadFailureKind.TIMEOUT,
            retryable = true,
            shouldRefreshSource = false,
            message = "timeout",
        )

        tracker.recordFailure(regional, timeout, 5_000L)
        tracker.recordFailure(regional, timeout, 5_000L)

        assertThat(tracker.shouldAttempt(regional)).isFalse()
        assertThat(tracker.shouldAttempt(global)).isTrue()
    }

    @Test
    fun successClosesRouteCircuitBreaker() {
        val tracker = DownloadRouteHealthTracker()
        val source = source("https://cache.libria.fun/video/playlist.m3u8")
        val timeout = DownloadFailure(
            kind = DownloadFailureKind.TIMEOUT,
            retryable = true,
            shouldRefreshSource = false,
            message = "timeout",
        )
        tracker.recordFailure(source, timeout, 5_000L)
        tracker.recordFailure(source, timeout, 5_000L)
        assertThat(tracker.shouldAttempt(source)).isFalse()

        tracker.recordSuccess(source, 100L)

        assertThat(tracker.shouldAttempt(source)).isTrue()
        assertThat(tracker.state(source)?.consecutiveFailures).isEqualTo(0)
    }

    private fun source(url: String) = DownloadMediaSource(
        url = url,
        headers = emptyMap(),
        providerId = "aniliberty",
    )
}
