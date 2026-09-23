package com.sergey.animevault.data.download

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host circuit breaker for offline downloads.
 *
 * Provider health and CDN health are intentionally separate: a dead regional AniLiberty edge must
 * not disable the working global edge (or the provider catalogue). Route state is process-local on
 * purpose; after an app restart every route gets a fresh chance.
 */
class DownloadRouteHealthTracker {
    private val states = ConcurrentHashMap<String, DownloadRouteHealthState>()

    fun shouldAttempt(source: DownloadMediaSource, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = routeKey(source) ?: return true
        return (states[key]?.cooldownUntilMs ?: 0L) <= nowMs
    }

    fun recordSuccess(source: DownloadMediaSource, latencyMs: Long) {
        val key = routeKey(source) ?: return
        states.compute(key) { _, previous ->
            DownloadRouteHealthState(
                routeKey = key,
                providerId = source.providerId,
                host = source.host,
                successfulRequests = (previous?.successfulRequests ?: 0) + 1,
                failedRequests = previous?.failedRequests ?: 0,
                consecutiveFailures = 0,
                latencyMs = latencyMs.coerceAtLeast(0L),
                lastSuccessAt = System.currentTimeMillis(),
                lastFailureAt = previous?.lastFailureAt,
                cooldownUntilMs = null,
                lastFailureKind = null,
            )
        }
    }

    internal fun recordFailure(source: DownloadMediaSource, failure: DownloadFailure, latencyMs: Long) {
        val key = routeKey(source) ?: return
        val now = System.currentTimeMillis()
        states.compute(key) { _, previous ->
            val streak = (previous?.consecutiveFailures ?: 0) + 1
            DownloadRouteHealthState(
                routeKey = key,
                providerId = source.providerId,
                host = source.host,
                successfulRequests = previous?.successfulRequests ?: 0,
                failedRequests = (previous?.failedRequests ?: 0) + 1,
                consecutiveFailures = streak,
                latencyMs = latencyMs.coerceAtLeast(0L),
                lastSuccessAt = previous?.lastSuccessAt,
                lastFailureAt = now,
                cooldownUntilMs = cooldownUntil(failure, streak, now),
                lastFailureKind = failure.kind,
            )
        }
    }

    fun state(source: DownloadMediaSource): DownloadRouteHealthState? = routeKey(source)?.let(states::get)

    fun snapshot(): List<DownloadRouteHealthState> = states.values
        .sortedWith(compareBy<DownloadRouteHealthState> { it.providerId.orEmpty() }.thenBy { it.host.orEmpty() })

    private fun cooldownUntil(failure: DownloadFailure, streak: Int, nowMs: Long): Long? {
        val threshold = when (failure.kind) {
            DownloadFailureKind.DNS, DownloadFailureKind.CONNECTION, DownloadFailureKind.TIMEOUT -> 2
            else -> 3
        }
        if (streak < threshold) return null
        val duration = when (failure.kind) {
            DownloadFailureKind.RATE_LIMITED -> 5 * 60_000L
            DownloadFailureKind.FORBIDDEN, DownloadFailureKind.AUTH_REQUIRED -> 2 * 60_000L
            DownloadFailureKind.DNS, DownloadFailureKind.CONNECTION, DownloadFailureKind.TIMEOUT,
            DownloadFailureKind.TLS, DownloadFailureKind.SERVER, DownloadFailureKind.NETWORK -> 90_000L
            else -> 60_000L
        }
        return nowMs + duration
    }

    private fun routeKey(source: DownloadMediaSource): String? {
        val host = source.host?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        return "${source.providerId.orEmpty()}|$host"
    }
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
