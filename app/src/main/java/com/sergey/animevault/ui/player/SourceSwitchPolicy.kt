package com.sergey.animevault.ui.player

import com.sergey.animevault.data.playback.PlaybackVariant

/** Unknown provenance must not silently cross the provider boundary either. */
internal fun requiresSourceSwitchConfirmation(current: PlaybackVariant, next: PlaybackVariant): Boolean =
    current.key != next.key && (current.providerId.isNullOrBlank() || next.providerId.isNullOrBlank() ||
        current.providerId != next.providerId)

/** One gate shared by recovery, URL refresh and adaptive quality paths. */
internal fun shouldDeferSourceSwitch(current: PlaybackVariant, next: PlaybackVariant,
    explicitSelection: Boolean, confirmed: Boolean): Boolean =
    !explicitSelection && !confirmed && requiresSourceSwitchConfirmation(current, next)

enum class SourceSwitchDecision { ALLOW, CONFIRM_FROM_START, CONFIRM_WITH_POSITION, BLOCK }
internal fun sourceSwitchDecision(current: PlaybackVariant, next: PlaybackVariant,
    identity: com.sergey.animevault.data.online.EpisodeIdentityClass, automaticOptIn: Boolean,
    healthAllowsAuto: Boolean = true): SourceSwitchDecision {
    if (identity == com.sergey.animevault.data.online.EpisodeIdentityClass.CONFLICT) return SourceSwitchDecision.BLOCK
    val sameVoice = current.translationKey != null && current.translationKey == next.translationKey ||
        current.translation?.trim()?.lowercase()?.let { it == next.translation?.trim()?.lowercase() } == true
    val voiceUnknownOrChanged = !sameVoice && (current.translation != null || next.translation != null || current.translationKey != null || next.translationKey != null)
    if (!requiresSourceSwitchConfirmation(current, next)) return if (voiceUnknownOrChanged) SourceSwitchDecision.CONFIRM_WITH_POSITION else SourceSwitchDecision.ALLOW
    if (identity != com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED) return SourceSwitchDecision.CONFIRM_FROM_START
    return if (automaticOptIn && healthAllowsAuto && next.isNativePlayable && !voiceUnknownOrChanged) SourceSwitchDecision.ALLOW else SourceSwitchDecision.CONFIRM_WITH_POSITION
}
