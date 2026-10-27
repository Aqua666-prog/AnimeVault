package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.playback.PlaybackVariant
import com.sergey.animevault.data.playback.PlaybackVariantKind
import org.junit.Test

class SourceSwitchPolicyTest {
    private val current = PlaybackVariant("a", "ep", "https://example.invalid/a.m3u8", PlaybackVariantKind.HLS, providerId = "one", translation = "Fixture Voice",
        episodeIdentity = com.sergey.animevault.data.online.EpisodeIdentity("one", "release", "ep"))
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
    @Test fun unavailableVoiceIsNeverSilentlyAccepted() {
        val dub = current.copy(translation = "Studio", sourceName = "Озвучка", translationKey = "voice-id")
        assertThat(matchesSavedVoice(dub, "voice-id", "Studio", "Озвучка")).isTrue()
        assertThat(matchesSavedVoice(dub, null, "Other Studio", "Озвучка")).isFalse()
        assertThat(matchesSavedVoice(dub.copy(sourceName = "Субтитры", translationKey = "sub-id"), "voice-id", "Studio", "Озвучка")).isFalse()
        assertThat(sourceSwitchDecision(dub, dub.copy(key = "sub", sourceName = "Субтитры", translationKey = "sub-id"),
            com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED, true)).isEqualTo(SourceSwitchDecision.CONFIRM_WITH_POSITION)
    }
    @Test fun unknownVoiceCannotBeAutomaticallyReplacedEvenWithVerifiedEpisode() {
        val unknown = current.copy(translation = null)
        assertThat(sourceSwitchDecision(unknown, unknown.copy(key = "b", providerId = "two"),
            com.sergey.animevault.data.online.EpisodeIdentityClass.VERIFIED, true)).isEqualTo(SourceSwitchDecision.CONFIRM_WITH_POSITION)
    }
    @Test fun sameProviderDoesNotProveAnotherReleaseIsTheSameEpisode() {
        val otherRelease = current.copy(key = "other-release", episodeIdentity = current.episodeIdentity!!.copy(releaseId = "remake"))
        assertThat(requiresSourceSwitchConfirmation(current, otherRelease)).isTrue()
        assertThat(sourceSwitchDecision(current, otherRelease, com.sergey.animevault.data.online.EpisodeIdentityClass.POSSIBLE, true))
            .isEqualTo(SourceSwitchDecision.CONFIRM_FROM_START)
        val unknown = current.copy(episodeIdentity = null)
        assertThat(requiresSourceSwitchConfirmation(unknown, unknown.copy(key = "unknown-cdn"))).isTrue()
    }
    @Test fun currentStreamIsNotANewSwitch() {
        assertThat(requiresSourceSwitchConfirmation(current, current)).isFalse()
    }
}
