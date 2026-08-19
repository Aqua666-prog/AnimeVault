package com.sergey.animevault.data.online

import com.sergey.animevault.data.playback.PlaybackFailureClassifier
import com.sergey.animevault.data.playback.PlaybackFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runtime telemetry for online providers.
 *
 * Health is updated by real catalog/release/stream operations, not only by the
 * manual diagnostics button. This lets the resolver and settings UI share one
 * view of which provider has actually been reliable during the current app run.
 */
class ProviderHealthTracker {
    private val _states = MutableStateFlow<Map<String, ProviderHealthState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderHealthState>> = _states.asStateFlow()

    fun register(providerIds: Collection<String>) {
        if (providerIds.isEmpty()) return
        _states.update { current ->
            buildMap {
                putAll(current)
                providerIds.forEach { providerId ->
                    putIfAbsent(providerId, ProviderHealthState(providerId = providerId))
                }
            }
        }
    }

    fun markChecking(providerId: String, message: String = "Проверяем соединение") {
        update(providerId) { current ->
            current.copy(
                status = ProviderHealthStatus.CHECKING,
                message = message,
            )
        }
    }

    fun markNeedsConfiguration(providerId: String, message: String) {
        val now = System.currentTimeMillis()
        update(providerId) { current ->
            current.copy(
                status = ProviderHealthStatus.NEEDS_CONFIGURATION,
                message = message,
                checkedAt = now,
            )
        }
    }

    fun reset(providerId: String) {
        update(providerId) { ProviderHealthState(providerId = providerId) }
    }

    fun recordSuccess(
        providerId: String,
        operation: ProviderOperation,
        latencyMs: Long,
        message: String? = null,
    ): ProviderHealthState {
        val now = System.currentTimeMillis()
        return update(providerId) { current ->
            current.copy(
                status = ProviderHealthStatus.AVAILABLE,
                latencyMs = latencyMs.coerceAtLeast(0L),
                message = message ?: operation.successMessage,
                checkedAt = now,
                lastSuccessAt = now,
                consecutiveFailures = 0,
                successfulRequests = current.successfulRequests + 1,
                lastOperation = operation,
                lastFailureKind = null,
                cooldownUntilMs = null,
            )
        }
    }

    fun recordFailure(
        providerId: String,
        operation: ProviderOperation,
        latencyMs: Long,
        error: Throwable,
        sourceName: String = "Источник",
    ): ProviderHealthState {
        val failure = PlaybackFailureClassifier.classify(error)
        val now = System.currentTimeMillis()
        return update(providerId) { current ->
            current.copy(
                status = if (current.lastSuccessAt != null) ProviderHealthStatus.DEGRADED else ProviderHealthStatus.UNAVAILABLE,
                latencyMs = latencyMs.coerceAtLeast(0L),
                message = failure.userMessage(sourceName),
                checkedAt = now,
                lastFailureAt = now,
                consecutiveFailures = current.consecutiveFailures + 1,
                failedRequests = current.failedRequests + 1,
                lastOperation = operation,
                lastFailureKind = failure.kind,
                cooldownUntilMs = cooldownUntilFor(
                    failureKind = failure.kind,
                    consecutiveFailures = current.consecutiveFailures + 1,
                    nowMs = now,
                ),
            )
        }
    }

    fun shouldAttempt(providerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cooldown = _states.value[providerId]?.cooldownUntilMs ?: return true
        return cooldown <= nowMs
    }

    fun cooldownRemainingMs(providerId: String, nowMs: Long = System.currentTimeMillis()): Long =
        ((_states.value[providerId]?.cooldownUntilMs ?: nowMs) - nowMs).coerceAtLeast(0L)

    suspend fun <T> track(
        providerId: String,
        operation: ProviderOperation,
        sourceName: String = "Источник",
        bypassCircuitBreaker: Boolean = false,
        block: suspend () -> T,
    ): T {
        if (!bypassCircuitBreaker && !shouldAttempt(providerId)) {
            val seconds = (cooldownRemainingMs(providerId) / 1_000L).coerceAtLeast(1L)
            throw OnlineSourceException("$sourceName временно пропущен после серии ошибок, повтор через ${seconds}с")
        }
        val startedAt = monotonicNowMs()
        return try {
            block().also {
                recordSuccess(
                    providerId = providerId,
                    operation = operation,
                    latencyMs = monotonicNowMs() - startedAt,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            recordFailure(
                providerId = providerId,
                operation = operation,
                latencyMs = monotonicNowMs() - startedAt,
                error = error,
                sourceName = sourceName,
            )
            throw error
        }
    }

    private fun cooldownUntilFor(
        failureKind: PlaybackFailureKind,
        consecutiveFailures: Int,
        nowMs: Long,
    ): Long? {
        if (consecutiveFailures < FAILURE_STREAK_FOR_COOLDOWN) return null
        val duration = when (failureKind) {
            PlaybackFailureKind.AUTH_REQUIRED, PlaybackFailureKind.FORBIDDEN -> 10 * 60_000L
            PlaybackFailureKind.RATE_LIMITED -> 5 * 60_000L
            PlaybackFailureKind.DNS, PlaybackFailureKind.TIMEOUT, PlaybackFailureKind.CONNECTION,
            PlaybackFailureKind.TLS, PlaybackFailureKind.NETWORK, PlaybackFailureKind.SERVER -> 90_000L
            else -> 60_000L
        }
        return nowMs + duration
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val FAILURE_STREAK_FOR_COOLDOWN = 3
    }

    private fun update(
        providerId: String,
        transform: (ProviderHealthState) -> ProviderHealthState,
    ): ProviderHealthState {
        var result = ProviderHealthState(providerId = providerId)
        _states.update { current ->
            result = transform(current[providerId] ?: result)
            current + (providerId to result)
        }
        return result
    }
}

enum class ProviderOperation(val successMessage: String) {
    CATALOG("Каталог отвечает"),
    RELEASE("Карточка тайтла отвечает"),
    STREAM("Поток получен"),
    HEALTH_CHECK("Источник отвечает"),
}

/**
 * 0..100 runtime score. It intentionally uses a small Bayesian prior so a
 * single lucky request cannot instantly turn a flaky provider into 100/100.
 */
val ProviderHealthState.healthScore: Int
    get() {
        if (status == ProviderHealthStatus.NEEDS_CONFIGURATION) return 20
        if (status == ProviderHealthStatus.UNKNOWN) return 50
        if (status == ProviderHealthStatus.CHECKING && successfulRequests + failedRequests == 0) return 50

        val total = successfulRequests + failedRequests
        val reliability = (successfulRequests + 2.0) / (total + 4.0)
        val reliabilityPoints = (reliability * 65.0).toInt()
        val latencyPoints = when (latencyMs) {
            null -> 10
            in 0L..249L -> 25
            in 250L..749L -> 21
            in 750L..1_499L -> 16
            in 1_500L..2_999L -> 10
            in 3_000L..5_999L -> 5
            else -> 2
        }
        val recentSuccessPoints = if (lastSuccessAt != null) 10 else 0
        val streakPenalty = (consecutiveFailures.coerceAtMost(3) * 12)
        val raw = reliabilityPoints + latencyPoints + recentSuccessPoints - streakPenalty
        return when (status) {
            ProviderHealthStatus.UNAVAILABLE -> raw.coerceIn(0, 45)
            ProviderHealthStatus.DEGRADED -> raw.coerceIn(20, 70)
            ProviderHealthStatus.AVAILABLE -> raw.coerceIn(35, 100)
            ProviderHealthStatus.CHECKING -> raw.coerceIn(20, 100)
            ProviderHealthStatus.NEEDS_CONFIGURATION -> 20
            ProviderHealthStatus.UNKNOWN -> 50
        }
    }

internal fun PlaybackFailureKind.shortHealthLabel(): String = when (this) {
    PlaybackFailureKind.TIMEOUT -> "таймаут"
    PlaybackFailureKind.DNS -> "DNS"
    PlaybackFailureKind.CONNECTION -> "соединение"
    PlaybackFailureKind.TLS -> "TLS"
    PlaybackFailureKind.AUTH_REQUIRED -> "авторизация"
    PlaybackFailureKind.FORBIDDEN -> "доступ запрещён"
    PlaybackFailureKind.NOT_FOUND -> "не найдено"
    PlaybackFailureKind.RATE_LIMITED -> "лимит запросов"
    PlaybackFailureKind.SERVER -> "сервер"
    PlaybackFailureKind.DECODER -> "декодер"
    PlaybackFailureKind.UNSUPPORTED_STREAM -> "формат потока"
    PlaybackFailureKind.NETWORK -> "сеть"
    PlaybackFailureKind.UNKNOWN -> "неизвестная ошибка"
}
