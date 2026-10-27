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
