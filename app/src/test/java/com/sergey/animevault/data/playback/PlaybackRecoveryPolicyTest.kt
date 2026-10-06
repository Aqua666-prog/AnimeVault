package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun transientFailureRetriesCurrentVariantWithBackoff() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val fallback = variant("fallback", 720, "Voice", "cdn-b.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.TIMEOUT),
            retryCountForVariant = 0,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = false,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.RetryCurrent(delayMs = 900L))
    }

    @Test
    fun secondConnectionFailureUsesLongerBackoffBeforeFallback() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val fallback = variant("fallback", 720, "Voice", "cdn-b.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.CONNECTION),
            retryCountForVariant = 1,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = false,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.RetryCurrent(delayMs = 1_200L))
    }

    @Test
    fun exhaustedTransientRetriesFallBackToClosestVariant() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val sameVoice = variant("same-voice", 720, "Voice", "cdn-b.example")
        val otherVoice = variant("other-voice", 1080, "Other", "cdn-c.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, otherVoice, sameVoice),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.NETWORK),
            retryCountForVariant = 2,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = false,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.SwitchVariant(sameVoice))
    }

    @Test
    fun coolingDownMirrorIsSkippedDuringFallback() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val blocked = variant("blocked", 1080, "Voice", "cdn-b.example")
        val healthy = variant("healthy", 720, "Voice", "cdn-c.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, blocked, healthy),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.NETWORK),
            retryCountForVariant = 2,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = true,
            blockedHostFamilies = setOf("cdn-b.example"),
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.SwitchVariant(healthy))
    }

    @Test
    fun refreshableExpiredLinkIsRefreshedBeforeRetryAndFallback() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val fallback = variant("fallback", 720, "Voice", "cdn-b.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
            retryCountForVariant = 0,
            canRefreshCurrent = true,
            refreshAlreadyAttempted = false,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.RefreshCurrent)
    }

    @Test
    fun failedRefreshDoesNotLoopAndCanFallBack() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val fallback = variant("fallback", 720, "Voice", "cdn-b.example")

        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = emptySet(),
            failure = PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
            retryCountForVariant = 0,
            canRefreshCurrent = true,
            refreshAlreadyAttempted = true,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.SwitchVariant(fallback))
    }

    @Test
    fun authFailureWithoutRefreshIsSurfaced() {
        val current = variant("current", 1080, "Voice", "cdn-a.example")
        val fallback = variant("fallback", 720, "Voice", "cdn-b.example")

        val failure = PlaybackFailure(PlaybackFailureKind.AUTH_REQUIRED, httpCode = 401)
        val action = PlaybackRecoveryPolicy.decide(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = emptySet(),
            failure = failure,
            retryCountForVariant = 0,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = false,
        )

        assertThat(action).isEqualTo(PlaybackRecoveryAction.GiveUp(failure))
    }

    private fun variant(
        key: String,
        quality: Int,
        translation: String,
        host: String,
    ) = PlaybackVariant(
        key = key,
        episodeKey = "episode:1",
        uri = "https://$host/$key.m3u8",
        kind = PlaybackVariantKind.HLS,
        providerId = "provider",
        providerName = "Provider",
        sourceName = "CDN",
        translation = translation,
        quality = quality,
        hostFamily = host,
    )

    @Test
    fun `HLS retries reuse the Media3 instance while MP4 can rebuild`() {
        assertThat(PlaybackPlayerRetryPolicy.reusePlayerInstance(PlaybackVariantKind.HLS)).isTrue()
        assertThat(PlaybackPlayerRetryPolicy.reusePlayerInstance(PlaybackVariantKind.MP4)).isFalse()
        assertThat(PlaybackPlayerRetryPolicy.reusePlayerInstance(PlaybackVariantKind.LOCAL)).isFalse()
    }
}
