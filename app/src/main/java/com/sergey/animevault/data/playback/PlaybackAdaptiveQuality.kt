package com.sergey.animevault.data.playback

import java.util.Locale

/**
 * Conservative adaptive-quality state for transport degradation.
 *
 * Automatic switches never overwrite the user's saved preferred quality. [recoveryTargetQuality]
 * remembers the quality that was active before an automatic downshift so the player can restore it
 * only after a long stable interval.
 */
data class PlaybackAdaptiveQualityState(
    val degradationEventsMs: List<Long> = emptyList(),
    val lastAutomaticSwitchAtMs: Long? = null,
    val recoveryTargetQuality: Int? = null,
) {
    fun recentEvents(nowMs: Long): List<Long> = degradationEventsMs.filter {
        nowMs - it in 0L..PlaybackAdaptiveQualityPolicy.EVENT_WINDOW_MS
    }
}

object PlaybackAdaptiveQualityPolicy {
    const val EVENT_WINDOW_MS: Long = 45_000L
    const val MIN_EVENTS_TO_DOWNSHIFT: Int = 2
    const val SWITCH_COOLDOWN_MS: Long = 30_000L
    const val STABLE_UPSHIFT_DELAY_MS: Long = 90_000L

    fun registerDegradation(
        state: PlaybackAdaptiveQualityState,
        failure: PlaybackFailure,
        nowMs: Long,
    ): PlaybackAdaptiveQualityState {
        if (!isAdaptiveFailure(failure.kind)) {
            return state.copy(degradationEventsMs = state.recentEvents(nowMs))
        }
        return state.copy(
            degradationEventsMs = (state.recentEvents(nowMs) + nowMs).takeLast(8),
        )
    }

    fun shouldDownshift(
        state: PlaybackAdaptiveQualityState,
        nowMs: Long,
    ): Boolean {
        if (state.recentEvents(nowMs).size < MIN_EVENTS_TO_DOWNSHIFT) return false
        val lastSwitch = state.lastAutomaticSwitchAtMs ?: return true
        return nowMs - lastSwitch >= SWITCH_COOLDOWN_MS
    }

    fun markAutomaticDownshift(
        state: PlaybackAdaptiveQualityState,
        fromQuality: Int?,
        nowMs: Long,
    ): PlaybackAdaptiveQualityState = state.copy(
        degradationEventsMs = emptyList(),
        lastAutomaticSwitchAtMs = nowMs,
        recoveryTargetQuality = listOfNotNull(state.recoveryTargetQuality, fromQuality).maxOrNull(),
    )

    fun markRecoveryUpshift(
        state: PlaybackAdaptiveQualityState,
        selectedQuality: Int?,
        nowMs: Long,
    ): PlaybackAdaptiveQualityState {
        val target = state.recoveryTargetQuality
        val reachedTarget = target != null && selectedQuality != null && selectedQuality >= target
        return state.copy(
            degradationEventsMs = emptyList(),
            lastAutomaticSwitchAtMs = nowMs,
            recoveryTargetQuality = if (reachedTarget) null else target,
        )
    }

    fun hasHealthySameQualityAlternative(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        failedVariantKeys: Set<String>,
        blockedHostFamilies: Set<String>,
    ): Boolean {
        val currentQuality = current.quality ?: return false
        val currentTranslationKey = current.translationKey.normalized()
        val currentTranslation = current.translation.normalized()
        val blocked = blockedHostFamilies.map { it.lowercase(Locale.ROOT) }.toSet()
        return variants.any { candidate ->
            candidate.key != current.key &&
                candidate.key !in failedVariantKeys &&
                candidate.uri.isNotBlank() &&
                candidate.isNativePlayable &&
                candidate.quality == currentQuality &&
                (candidate.normalizedHostFamily()?.lowercase(Locale.ROOT)?.let { it !in blocked } ?: true) &&
                sameTranslation(candidate, currentTranslationKey, currentTranslation)
        }
    }

    fun selectDownshift(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        failedVariantKeys: Set<String>,
        blockedHostFamilies: Set<String>,
    ): PlaybackVariant? {
        val currentQuality = current.quality ?: return null
        if (current.isLocal || !current.isNativePlayable || currentQuality <= MIN_ADAPTIVE_QUALITY) return null

        val currentTranslationKey = current.translationKey.normalized()
        val currentTranslation = current.translation.normalized()
        val currentProvider = current.providerId.normalized()
        val currentSource = current.sourceName.normalized()
        val blocked = blockedHostFamilies.map { it.lowercase(Locale.ROOT) }.toSet()

        return variants.asSequence()
            .filter { it.key != current.key && it.key !in failedVariantKeys }
            .filter { it.uri.isNotBlank() && !it.isLocal && it.isNativePlayable }
            .filter { candidate -> candidate.quality?.let { it < currentQuality } == true }
            .filter { candidate ->
                candidate.normalizedHostFamily()?.lowercase(Locale.ROOT)?.let { it !in blocked } ?: true
            }
            .filter { candidate -> sameTranslation(candidate, currentTranslationKey, currentTranslation) }
            .sortedWith(
                compareByDescending<PlaybackVariant> {
                    currentProvider != null && it.providerId.normalized() == currentProvider
                }.thenByDescending {
                    currentSource != null && it.sourceName.normalized() == currentSource
                }.thenByDescending { it.quality ?: 0 }
                    .thenBy(PlaybackVariant::displayName),
            )
            .firstOrNull()
    }

    /**
     * Restores quality after a long stable period. The original translation is preserved and a
     * route still in cooldown is never selected. The target is capped at the pre-downshift quality.
     */
    fun selectRecoveryUpshift(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        state: PlaybackAdaptiveQualityState,
        failedVariantKeys: Set<String>,
        blockedHostFamilies: Set<String>,
    ): PlaybackVariant? {
        val currentQuality = current.quality ?: return null
        val targetQuality = state.recoveryTargetQuality ?: return null
        if (targetQuality <= currentQuality || current.isLocal || !current.isNativePlayable) return null

        val currentTranslationKey = current.translationKey.normalized()
        val currentTranslation = current.translation.normalized()
        val currentProvider = current.providerId.normalized()
        val blocked = blockedHostFamilies.map { it.lowercase(Locale.ROOT) }.toSet()

        return variants.asSequence()
            .filter { it.key != current.key && it.key !in failedVariantKeys }
            .filter { it.uri.isNotBlank() && !it.isLocal && it.isNativePlayable }
            .filter { candidate -> candidate.quality?.let { it in (currentQuality + 1)..targetQuality } == true }
            .filter { candidate ->
                candidate.normalizedHostFamily()?.lowercase(Locale.ROOT)?.let { it !in blocked } ?: true
            }
            .filter { candidate -> sameTranslation(candidate, currentTranslationKey, currentTranslation) }
            .sortedWith(
                compareByDescending<PlaybackVariant> {
                    currentProvider != null && it.providerId.normalized() == currentProvider
                }.thenByDescending { it.quality ?: 0 }
                    .thenBy(PlaybackVariant::displayName),
            )
            .firstOrNull()
    }

    fun isAdaptiveFailure(kind: PlaybackFailureKind): Boolean = when (kind) {
        PlaybackFailureKind.TIMEOUT,
        PlaybackFailureKind.CONNECTION,
        PlaybackFailureKind.SERVER,
        PlaybackFailureKind.NETWORK,
        -> true

        else -> false
    }

    private fun sameTranslation(
        candidate: PlaybackVariant,
        currentTranslationKey: String?,
        currentTranslation: String?,
    ): Boolean = when {
        currentTranslationKey != null -> candidate.translationKey.normalized() == currentTranslationKey
        currentTranslation != null -> candidate.translation.normalized() == currentTranslation
        else -> true
    }

    private fun String?.normalized(): String? = this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.lowercase(Locale.ROOT)

    private const val MIN_ADAPTIVE_QUALITY = 360
}
