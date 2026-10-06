package com.sergey.animevault.data.playback

/**
 * Deterministic recovery state machine for online playback.
 *
 * The UI owns counters/session lifetime; this policy only decides the next action. That keeps
 * recovery reusable by Compose, TV and a future background playback service.
 */
sealed interface PlaybackRecoveryAction {
    data class RetryCurrent(val delayMs: Long) : PlaybackRecoveryAction
    data object RefreshCurrent : PlaybackRecoveryAction
    data class SwitchVariant(val variant: PlaybackVariant) : PlaybackRecoveryAction
    data class GiveUp(val failure: PlaybackFailure) : PlaybackRecoveryAction
}

object PlaybackRecoveryPolicy {
    fun decide(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        failedVariantKeys: Set<String>,
        failure: PlaybackFailure,
        retryCountForVariant: Int,
        canRefreshCurrent: Boolean,
        refreshAlreadyAttempted: Boolean,
        blockedHostFamilies: Set<String> = emptySet(),
    ): PlaybackRecoveryAction {
        if (canRefreshCurrent && !refreshAlreadyAttempted) {
            return PlaybackRecoveryAction.RefreshCurrent
        }

        if (retryCountForVariant < maxRetriesFor(failure.kind) && shouldRetryCurrent(failure.kind)) {
            return PlaybackRecoveryAction.RetryCurrent(
                delayMs = retryDelayMs(
                    kind = failure.kind,
                    retryCountForVariant = retryCountForVariant,
                ),
            )
        }

        val failed = failedVariantKeys + current.key
        val fallback = PlaybackVariantResolver.selectFallback(
            variants = variants,
            current = current,
            failedVariantKeys = failed,
            failure = failure,
            blockedHostFamilies = blockedHostFamilies,
        )
        return fallback
            ?.let(PlaybackRecoveryAction::SwitchVariant)
            ?: PlaybackRecoveryAction.GiveUp(failure)
    }

    fun shouldRetryCurrent(kind: PlaybackFailureKind): Boolean = when (kind) {
        PlaybackFailureKind.TIMEOUT,
        PlaybackFailureKind.CONNECTION,
        PlaybackFailureKind.SERVER,
        PlaybackFailureKind.NETWORK,
        -> true

        PlaybackFailureKind.DNS,
        PlaybackFailureKind.TLS,
        PlaybackFailureKind.AUTH_REQUIRED,
        PlaybackFailureKind.FORBIDDEN,
        PlaybackFailureKind.NOT_FOUND,
        PlaybackFailureKind.RATE_LIMITED,
        PlaybackFailureKind.DECODER,
        PlaybackFailureKind.UNSUPPORTED_STREAM,
        PlaybackFailureKind.UNKNOWN,
        -> false
    }

    internal fun maxRetriesFor(kind: PlaybackFailureKind): Int = when (kind) {
        PlaybackFailureKind.CONNECTION,
        PlaybackFailureKind.NETWORK,
        -> 2

        PlaybackFailureKind.TIMEOUT,
        PlaybackFailureKind.SERVER,
        -> 1

        else -> 0
    }

    /** Small deterministic backoff; no jitter so tests and UX stay predictable. */
    internal fun retryDelayMs(
        kind: PlaybackFailureKind,
        retryCountForVariant: Int,
    ): Long {
        val base = when (kind) {
            PlaybackFailureKind.NETWORK -> 400L
            PlaybackFailureKind.CONNECTION -> 600L
            PlaybackFailureKind.TIMEOUT -> 900L
            PlaybackFailureKind.SERVER -> 1_250L
            else -> 0L
        }
        if (base == 0L) return 0L
        val multiplier = 1L shl retryCountForVariant.coerceIn(0, 3)
        return (base * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private const val MAX_RETRY_DELAY_MS = 5_000L
}

/** Media3 execution detail kept outside Compose so retry semantics are testable. */
object PlaybackPlayerRetryPolicy {
    /** HLS can be re-prepared in-place; keeping the player preserves decoder/cache/session state. */
    fun reusePlayerInstance(kind: PlaybackVariantKind): Boolean = kind == PlaybackVariantKind.HLS
}
