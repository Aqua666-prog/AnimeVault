package com.sergey.animevault.data.transport

import java.util.concurrent.ConcurrentHashMap

/** Shared process-local circuit breaker for concrete provider + CDN routes. */
class TransportRouteHealthTracker(
    private val policy: TransportRouteHealthPolicy,
) {
    private val states = ConcurrentHashMap<String, TransportRouteHealthState>()

    fun shouldAttempt(source: MediaTransportSource, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = source.routeKey ?: return true
        return (states[key]?.cooldownUntilMs ?: 0L) <= nowMs
    }

    fun recordSuccess(
        source: MediaTransportSource,
        latencyMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = source.routeKey ?: return
        states.compute(key) { _, previous ->
            TransportRouteHealthState(
                routeKey = key,
                providerId = source.providerId,
                routeFamily = source.normalizedRouteFamily,
                successfulRequests = (previous?.successfulRequests ?: 0) + 1,
                failedRequests = previous?.failedRequests ?: 0,
                consecutiveFailures = 0,
                latencyMs = latencyMs?.coerceAtLeast(0L) ?: previous?.latencyMs,
                lastSuccessAtMs = nowMs,
                lastFailureAtMs = previous?.lastFailureAtMs,
                lastFailureKind = null,
                cooldownUntilMs = null,
            )
        }
    }

    fun recordFailure(
        source: MediaTransportSource,
        kind: TransportFailureKind,
        latencyMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = source.routeKey ?: return
        states.compute(key) { _, previous ->
            val streak = (previous?.consecutiveFailures ?: 0) + 1
            TransportRouteHealthState(
                routeKey = key,
                providerId = source.providerId,
                routeFamily = source.normalizedRouteFamily,
                successfulRequests = previous?.successfulRequests ?: 0,
                failedRequests = (previous?.failedRequests ?: 0) + 1,
                consecutiveFailures = streak,
                latencyMs = latencyMs?.coerceAtLeast(0L) ?: previous?.latencyMs,
                lastSuccessAtMs = previous?.lastSuccessAtMs,
                lastFailureAtMs = nowMs,
                lastFailureKind = kind,
                cooldownUntilMs = policy.cooldownUntil(kind, streak, nowMs),
            )
        }
    }

    /** Explicit user selection gives this route a clean slate. */
    fun forgive(source: MediaTransportSource) {
        source.routeKey?.let(states::remove)
    }

    fun state(source: MediaTransportSource): TransportRouteHealthState? = source.routeKey?.let(states::get)

    fun blockedRouteFamilies(nowMs: Long = System.currentTimeMillis()): Set<String> = states.values
        .asSequence()
        .filter { (it.cooldownUntilMs ?: 0L) > nowMs }
        .mapNotNull(TransportRouteHealthState::routeFamily)
        .toSet()

    fun cooldownRemainingMs(
        source: MediaTransportSource,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val key = source.routeKey ?: return 0L
        return ((states[key]?.cooldownUntilMs ?: nowMs) - nowMs).coerceAtLeast(0L)
    }

    fun snapshot(): List<TransportRouteHealthState> = states.values.sortedWith(
        compareBy<TransportRouteHealthState> { it.providerId.orEmpty() }
            .thenBy { it.routeFamily.orEmpty() },
    )
}

interface TransportRouteHealthPolicy {
    fun cooldownUntil(kind: TransportFailureKind, streak: Int, nowMs: Long): Long?
}

object PlaybackTransportRoutePolicy : TransportRouteHealthPolicy {
    override fun cooldownUntil(kind: TransportFailureKind, streak: Int, nowMs: Long): Long? {
        val threshold = when (kind) {
            TransportFailureKind.DNS,
            TransportFailureKind.TLS,
            TransportFailureKind.RATE_LIMITED,
            -> 1

            TransportFailureKind.TIMEOUT,
            TransportFailureKind.CONNECTION,
            TransportFailureKind.FORBIDDEN,
            TransportFailureKind.NOT_FOUND,
            TransportFailureKind.SERVER,
            TransportFailureKind.NETWORK,
            -> 2

            else -> Int.MAX_VALUE
        }
        if (streak < threshold) return null
        val durationMs = when (kind) {
            TransportFailureKind.RATE_LIMITED -> 120_000L
            TransportFailureKind.DNS, TransportFailureKind.TLS -> 60_000L
            TransportFailureKind.SERVER -> 45_000L
            TransportFailureKind.TIMEOUT,
            TransportFailureKind.CONNECTION,
            TransportFailureKind.NETWORK,
            -> 30_000L
            TransportFailureKind.FORBIDDEN,
            TransportFailureKind.NOT_FOUND,
            -> 20_000L
            else -> return null
        }
        return nowMs + durationMs
    }
}

object DownloadTransportRoutePolicy : TransportRouteHealthPolicy {
    override fun cooldownUntil(kind: TransportFailureKind, streak: Int, nowMs: Long): Long? {
        val threshold = when (kind) {
            TransportFailureKind.DNS,
            TransportFailureKind.CONNECTION,
            TransportFailureKind.TIMEOUT,
            -> 2
            else -> 3
        }
        if (streak < threshold) return null
        val durationMs = when (kind) {
            TransportFailureKind.RATE_LIMITED -> 5 * 60_000L
            TransportFailureKind.FORBIDDEN, TransportFailureKind.AUTH_REQUIRED -> 2 * 60_000L
            TransportFailureKind.DNS,
            TransportFailureKind.CONNECTION,
            TransportFailureKind.TIMEOUT,
            TransportFailureKind.TLS,
            TransportFailureKind.SERVER,
            TransportFailureKind.NETWORK,
            -> 90_000L
            else -> 60_000L
        }
        return nowMs + durationMs
    }
}

data class TransportRouteHealthState(
    val routeKey: String,
    val providerId: String?,
    val routeFamily: String?,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val latencyMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val lastFailureKind: TransportFailureKind? = null,
    val cooldownUntilMs: Long? = null,
)
