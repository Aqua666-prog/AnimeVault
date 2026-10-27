package com.sergey.animevault.ui.player

import com.sergey.animevault.data.playback.PlaybackVariant

/** Unknown provenance must not silently cross the provider boundary either. */
internal fun requiresSourceSwitchConfirmation(current: PlaybackVariant, next: PlaybackVariant): Boolean {
    if (current.key == next.key) return false
    if (current.providerId.isNullOrBlank() || next.providerId.isNullOrBlank() || current.providerId != next.providerId) return true
    val a = current.episodeIdentity
    val b = next.episodeIdentity
    return a == null || b == null || a.providerId != b.providerId || a.releaseId != b.releaseId || a.episodeId != b.episodeId
}

/** One gate shared by recovery, URL refresh and adaptive quality paths. */
internal fun shouldDeferSourceSwitch(current: PlaybackVariant, next: PlaybackVariant,
    explicitSelection: Boolean, confirmed: Boolean): Boolean =
    !explicitSelection && !confirmed && requiresSourceSwitchConfirmation(current, next)

enum class SourceSwitchDecision { ALLOW, CONFIRM_FROM_START, CONFIRM_WITH_POSITION, BLOCK }
internal fun sourceSwitchDecision(current: PlaybackVariant, next: PlaybackVariant,
    identity: com.sergey.animevault.data.online.EpisodeIdentityClass, automaticOptIn: Boolean,
    healthAllowsAuto: Boolean = true): SourceSwitchDecision {
    if (identity == com.sergey.animevault.data.online.EpisodeIdentityClass.CONFLICT) return SourceSwitchDecision.BLOCK
    if (!healthAllowsAuto) return if (identity == com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED)
        SourceSwitchDecision.CONFIRM_WITH_POSITION else SourceSwitchDecision.CONFIRM_FROM_START
    val voiceUnknownOrChanged = !matchesSavedVoice(next, current.translationKey, current.translation, current.sourceName)
    if (!requiresSourceSwitchConfirmation(current, next)) return if (voiceUnknownOrChanged) SourceSwitchDecision.CONFIRM_WITH_POSITION else SourceSwitchDecision.ALLOW
    if (identity != com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED) return SourceSwitchDecision.CONFIRM_FROM_START
    if (!current.providerId.isNullOrBlank() && current.providerId == next.providerId && !voiceUnknownOrChanged) return SourceSwitchDecision.ALLOW
    return if (automaticOptIn && healthAllowsAuto && next.isNativePlayable && !voiceUnknownOrChanged &&
        (!current.translation.isNullOrBlank() || current.translationKey != null && current.translationKey == next.translationKey)) SourceSwitchDecision.ALLOW else SourceSwitchDecision.CONFIRM_WITH_POSITION
}

internal fun matchesSavedVoice(variant: PlaybackVariant, key: String?, name: String?, source: String?): Boolean {
    if (key.isNullOrBlank() && name.isNullOrBlank()) return true
    if (!key.isNullOrBlank() && variant.translationKey == key) return true
    if (name.isNullOrBlank() || !name.trim().equals(variant.translation?.trim(), true)) return false
    fun kind(value: String?): String? = when {
        Regex("(?i)(субтитр|subtitle|\\bsubs?\\b)").containsMatchIn(value.orEmpty()) -> "subtitles"
        Regex("(?i)(озвуч|dub|voice)").containsMatchIn(value.orEmpty()) -> "voice"
        else -> null
    }
    val previousKind = kind(source)
    val nextKind = kind(variant.sourceName)
    return previousKind == null || nextKind == null || previousKind == nextKind
}
