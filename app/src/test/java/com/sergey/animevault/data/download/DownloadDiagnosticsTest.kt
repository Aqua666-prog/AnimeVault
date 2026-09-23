package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import org.junit.Test

class DownloadDiagnosticsTest {
    @Test
    fun progressEstimator_reportsSmoothedSpeedAndByteEta() {
        val estimator = DownloadProgressEstimator(smoothing = 1.0)

        estimator.sample(
            bytesDownloaded = 0L,
            contentLength = 10_000L,
            completedItems = 0,
            totalItems = 10,
            nowMs = 1_000L,
        )
        val result = estimator.sample(
            bytesDownloaded = 2_000L,
            contentLength = 10_000L,
            completedItems = 2,
            totalItems = 10,
            nowMs = 2_000L,
        )

        assertThat(result.speedBytesPerSecond).isEqualTo(2_000L)
        assertThat(result.etaSeconds).isEqualTo(4L)
    }

    @Test
    fun progressEstimator_fallsBackToItemEtaWhenContentLengthIsUnknown() {
        val estimator = DownloadProgressEstimator(smoothing = 1.0)

        estimator.sample(0L, -1L, 0, 10, nowMs = 1_000L)
        val result = estimator.sample(1_000L, -1L, 2, 10, nowMs = 2_000L)

        assertThat(result.speedBytesPerSecond).isEqualTo(1_000L)
        assertThat(result.etaSeconds).isEqualTo(4L)
    }

    @Test
    fun forbiddenHttpFailure_refreshesSourceInsteadOfBlindRetry() {
        val failure = DownloadFailureClassifier.classify(
            NativeDownloadHttpException(403, "HTTP 403"),
        )

        assertThat(failure.kind).isEqualTo(DownloadFailureKind.FORBIDDEN)
        assertThat(failure.retryable).isFalse()
        assertThat(failure.shouldRefreshSource).isTrue()
    }

    @Test
    fun timeoutIsRetryableWithoutForcingSourceRefresh() {
        val failure = DownloadFailureClassifier.classify(SocketTimeoutException("slow CDN"))

        assertThat(failure.kind).isEqualTo(DownloadFailureKind.TIMEOUT)
        assertThat(failure.retryable).isTrue()
        assertThat(failure.shouldRefreshSource).isFalse()
    }
}
