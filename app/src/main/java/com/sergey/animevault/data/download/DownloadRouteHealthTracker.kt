package com.sergey.animevault.data.download

import com.sergey.animevault.data.transport.DownloadTransportRoutePolicy
import com.sergey.animevault.data.transport.TransportFailureKind
import com.sergey.animevault.data.transport.TransportRouteHealthTracker

/** Download-facing wrapper around the shared provider + CDN route circuit breaker. */
class DownloadRouteHealthTracker {
    private val delegate = TransportRouteHealthTracker(DownloadTransportRoutePolicy)

    fun shouldAttempt(source: DownloadMediaSource, nowMs: Long = System.currentTimeMillis()): Boolean =
        delegate.shouldAttempt(source.toTransportSource(), nowMs)

    fun recordSuccess(source: DownloadMediaSource, latencyMs: Long) {
        delegate.recordSuccess(source.toTransportSource(), latencyMs = latencyMs)
    }

    internal fun recordFailure(source: DownloadMediaSource, failure: DownloadFailure, latencyMs: Long) {
        delegate.recordFailure(
            source = source.toTransportSource(),
            kind = failure.kind.toTransportFailureKind(),
            latencyMs = latencyMs,
        )
    }

    fun state(source: DownloadMediaSource): DownloadRouteHealthState? = delegate
        .state(source.toTransportSource())
        ?.toDownloadState()

    fun snapshot(): List<DownloadRouteHealthState> = delegate.snapshot().map { it.toDownloadState() }

    private fun com.sergey.animevault.data.transport.TransportRouteHealthState.toDownloadState() =
        DownloadRouteHealthState(
            routeKey = routeKey,
            providerId = providerId,
            host = routeFamily,
            successfulRequests = successfulRequests,
            failedRequests = failedRequests,
            consecutiveFailures = consecutiveFailures,
            latencyMs = latencyMs,
            lastSuccessAt = lastSuccessAtMs,
            lastFailureAt = lastFailureAtMs,
            cooldownUntilMs = cooldownUntilMs,
            lastFailureKind = lastFailureKind?.toDownloadFailureKind(),
        )
}

data class DownloadRouteHealthState(
    val routeKey: String,
    val providerId: String?,
    val host: String?,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val latencyMs: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val cooldownUntilMs: Long? = null,
    val lastFailureKind: DownloadFailureKind? = null,
)

internal fun DownloadFailureKind.toTransportFailureKind(): TransportFailureKind = when (this) {
    DownloadFailureKind.TIMEOUT -> TransportFailureKind.TIMEOUT
    DownloadFailureKind.DNS -> TransportFailureKind.DNS
    DownloadFailureKind.CONNECTION -> TransportFailureKind.CONNECTION
    DownloadFailureKind.TLS -> TransportFailureKind.TLS
    DownloadFailureKind.AUTH_REQUIRED -> TransportFailureKind.AUTH_REQUIRED
    DownloadFailureKind.FORBIDDEN -> TransportFailureKind.FORBIDDEN
    DownloadFailureKind.NOT_FOUND -> TransportFailureKind.NOT_FOUND
    DownloadFailureKind.RATE_LIMITED -> TransportFailureKind.RATE_LIMITED
    DownloadFailureKind.SERVER -> TransportFailureKind.SERVER
    DownloadFailureKind.UNSUPPORTED -> TransportFailureKind.UNSUPPORTED
    DownloadFailureKind.STORAGE -> TransportFailureKind.STORAGE
    DownloadFailureKind.VERIFICATION -> TransportFailureKind.VERIFICATION
    DownloadFailureKind.NETWORK -> TransportFailureKind.NETWORK
    DownloadFailureKind.UNKNOWN -> TransportFailureKind.UNKNOWN
}

private fun TransportFailureKind.toDownloadFailureKind(): DownloadFailureKind = when (this) {
    TransportFailureKind.TIMEOUT -> DownloadFailureKind.TIMEOUT
    TransportFailureKind.DNS -> DownloadFailureKind.DNS
    TransportFailureKind.CONNECTION -> DownloadFailureKind.CONNECTION
    TransportFailureKind.TLS -> DownloadFailureKind.TLS
    TransportFailureKind.AUTH_REQUIRED -> DownloadFailureKind.AUTH_REQUIRED
    TransportFailureKind.FORBIDDEN -> DownloadFailureKind.FORBIDDEN
    TransportFailureKind.NOT_FOUND -> DownloadFailureKind.NOT_FOUND
    TransportFailureKind.RATE_LIMITED -> DownloadFailureKind.RATE_LIMITED
    TransportFailureKind.SERVER -> DownloadFailureKind.SERVER
    TransportFailureKind.UNSUPPORTED, TransportFailureKind.DECODER -> DownloadFailureKind.UNSUPPORTED
    TransportFailureKind.STORAGE -> DownloadFailureKind.STORAGE
    TransportFailureKind.VERIFICATION -> DownloadFailureKind.VERIFICATION
    TransportFailureKind.NETWORK -> DownloadFailureKind.NETWORK
    TransportFailureKind.UNKNOWN -> DownloadFailureKind.UNKNOWN
}
