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
    @Test fun currentStreamIsNotANewSwitch() {
        assertThat(requiresSourceSwitchConfirmation(current, current)).isFalse()
    }
}
