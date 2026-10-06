package com.sergey.animevault.data.playback

import com.sergey.animevault.data.transport.PlaybackTransportRoutePolicy
import com.sergey.animevault.data.transport.TransportRouteHealthTracker

/**
 * Player-facing compatibility wrapper around the shared transport circuit breaker.
 */
class PlaybackRouteHealthTracker {
    private val delegate = TransportRouteHealthTracker(PlaybackTransportRoutePolicy)

    fun recordSuccess(variant: PlaybackVariant, nowMs: Long = System.currentTimeMillis()) {
        delegate.recordSuccess(variant.toTransportSource(), nowMs = nowMs)
    }

    fun recordFailure(
        variant: PlaybackVariant,
        failure: PlaybackFailure,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        delegate.recordFailure(
            source = variant.toTransportSource(),
            kind = failure.kind.toTransportFailureKind(),
            nowMs = nowMs,
        )
    }

    /** Manual selection is an explicit user override, so give that route a fresh chance. */
    fun forgive(variant: PlaybackVariant) {
        delegate.forgive(variant.toTransportSource())
    }

    fun blockedHostFamilies(nowMs: Long = System.currentTimeMillis()): Set<String> =
        delegate.blockedRouteFamilies(nowMs)

    fun isCoolingDown(variant: PlaybackVariant, nowMs: Long = System.currentTimeMillis()): Boolean =
        !delegate.shouldAttempt(variant.toTransportSource(), nowMs)

    fun cooldownRemainingMs(
        variant: PlaybackVariant,
        nowMs: Long = System.currentTimeMillis(),
    ): Long = delegate.cooldownRemainingMs(variant.toTransportSource(), nowMs)

    fun snapshot(): List<PlaybackRouteHealthState> = delegate.snapshot().map { state ->
        PlaybackRouteHealthState(
            routeKey = state.routeKey,
            providerId = state.providerId,
            hostFamily = state.routeFamily,
            successfulRequests = state.successfulRequests,
            failedRequests = state.failedRequests,
            consecutiveFailures = state.consecutiveFailures,
            lastSuccessAtMs = state.lastSuccessAtMs,
            lastFailureAtMs = state.lastFailureAtMs,
            lastFailureKind = state.lastFailureKind?.toPlaybackFailureKind(),
            cooldownUntilMs = state.cooldownUntilMs,
        )
    }
}

data class PlaybackRouteHealthState(
    val routeKey: String,
    val providerId: String?,
    val hostFamily: String?,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val lastFailureKind: PlaybackFailureKind? = null,
    val cooldownUntilMs: Long? = null,
)

internal fun PlaybackVariant.normalizedHostFamily(): String? = toTransportSource().normalizedRouteFamily

private fun com.sergey.animevault.data.transport.TransportFailureKind.toPlaybackFailureKind(): PlaybackFailureKind = when (this) {
    com.sergey.animevault.data.transport.TransportFailureKind.TIMEOUT -> PlaybackFailureKind.TIMEOUT
    com.sergey.animevault.data.transport.TransportFailureKind.DNS -> PlaybackFailureKind.DNS
    com.sergey.animevault.data.transport.TransportFailureKind.CONNECTION -> PlaybackFailureKind.CONNECTION
    com.sergey.animevault.data.transport.TransportFailureKind.TLS -> PlaybackFailureKind.TLS
    com.sergey.animevault.data.transport.TransportFailureKind.AUTH_REQUIRED -> PlaybackFailureKind.AUTH_REQUIRED
    com.sergey.animevault.data.transport.TransportFailureKind.FORBIDDEN -> PlaybackFailureKind.FORBIDDEN
    com.sergey.animevault.data.transport.TransportFailureKind.NOT_FOUND -> PlaybackFailureKind.NOT_FOUND
    com.sergey.animevault.data.transport.TransportFailureKind.RATE_LIMITED -> PlaybackFailureKind.RATE_LIMITED
    com.sergey.animevault.data.transport.TransportFailureKind.SERVER -> PlaybackFailureKind.SERVER
    com.sergey.animevault.data.transport.TransportFailureKind.DECODER -> PlaybackFailureKind.DECODER
    com.sergey.animevault.data.transport.TransportFailureKind.UNSUPPORTED -> PlaybackFailureKind.UNSUPPORTED_STREAM
    com.sergey.animevault.data.transport.TransportFailureKind.NETWORK -> PlaybackFailureKind.NETWORK
    com.sergey.animevault.data.transport.TransportFailureKind.STORAGE,
    com.sergey.animevault.data.transport.TransportFailureKind.VERIFICATION,
    com.sergey.animevault.data.transport.TransportFailureKind.UNKNOWN,
    -> PlaybackFailureKind.UNKNOWN
}
