package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.playback.PlaybackVariant
import com.sergey.animevault.data.playback.PlaybackVariantKind
import org.junit.Test

class SourceSwitchPolicyTest {
    private val current = PlaybackVariant("a", "ep", "https://example.invalid/a.m3u8", PlaybackVariantKind.HLS, providerId = "one")
    @Test fun sameProviderCdnRecoveryDoesNotNeedSourceConfirmation() {
        assertThat(requiresSourceSwitchConfirmation(current, current.copy(key = "b"))).isFalse()
    }
    @Test fun otherProviderOrUnknownProvenanceNeedsConfirmation() {
        assertThat(requiresSourceSwitchConfirmation(current, current.copy(key = "b", providerId = "two"))).isTrue()
        assertThat(requiresSourceSwitchConfirmation(current, current.copy(key = "b", providerId = null))).isTrue()
    }
    @Test fun automaticQualityOrRefreshCannotBypassCrossProviderConfirmation() {
        val other = current.copy(key = "b", providerId = "two", quality = 720)
        assertThat(shouldDeferSourceSwitch(current, other, false, false)).isTrue()
        assertThat(shouldDeferSourceSwitch(current, other, false, true)).isFalse()
        assertThat(shouldDeferSourceSwitch(current, other, true, false)).isFalse()
        assertThat(shouldDeferSourceSwitch(current, current.copy(key = "c"), false, false)).isFalse()
    }
    @Test fun identityAndOptInAreBothRequiredForAutomaticSwitch() {
        val other = current.copy(key = "other", providerId = "two")
        val verified = com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED
        assertThat(sourceSwitchDecision(current, other, verified, false)).isEqualTo(SourceSwitchDecision.CONFIRM_WITH_POSITION)
        assertThat(sourceSwitchDecision(current, other, verified, true)).isEqualTo(SourceSwitchDecision.ALLOW)
        assertThat(sourceSwitchDecision(current, other, verified, true, false)).isEqualTo(SourceSwitchDecision.CONFIRM_WITH_POSITION)
        listOf(com.sergey.animevault.data.online.EpisodeIdentityClass.POSSIBLE, com.sergey.animevault.data.online.EpisodeIdentityClass.UNKNOWN).forEach {
            assertThat(sourceSwitchDecision(current, other, it, true)).isEqualTo(SourceSwitchDecision.CONFIRM_FROM_START)
        }
        assertThat(sourceSwitchDecision(current, other, com.sergey.animevault.data.online.EpisodeIdentityClass.CONFLICT, true)).isEqualTo(SourceSwitchDecision.BLOCK)
    }
    @Test fun currentStreamIsNotANewSwitch() {
        assertThat(requiresSourceSwitchConfirmation(current, current)).isFalse()
    }
}
