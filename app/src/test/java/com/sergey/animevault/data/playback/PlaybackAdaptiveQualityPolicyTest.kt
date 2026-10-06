package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackAdaptiveQualityPolicyTest {
    @Test
    fun `two recent degradation events allow a downshift`() {
        val now = 100_000L
        val failure = PlaybackFailure(PlaybackFailureKind.TIMEOUT)
        val once = PlaybackAdaptiveQualityPolicy.registerDegradation(
            state = PlaybackAdaptiveQualityState(),
            failure = failure,
            nowMs = now - 1_000L,
        )
        val twice = PlaybackAdaptiveQualityPolicy.registerDegradation(
            state = once,
            failure = failure,
            nowMs = now,
        )

        assertThat(PlaybackAdaptiveQualityPolicy.shouldDownshift(twice, now)).isTrue()
    }

    @Test
    fun `old degradation events expire from the adaptive window`() {
        val state = PlaybackAdaptiveQualityState(
            degradationEventsMs = listOf(1_000L, 2_000L),
        )

        assertThat(
            PlaybackAdaptiveQualityPolicy.shouldDownshift(
                state = state,
                nowMs = PlaybackAdaptiveQualityPolicy.EVENT_WINDOW_MS + 10_000L,
            ),
        ).isFalse()
    }

    @Test
    fun `downshift keeps voice and picks highest lower quality`() {
        val current = variant("current", 1080, "AniLibria", "cdn-a")
        val sameVoice720 = variant("720", 720, "AniLibria", "cdn-a")
        val sameVoice480 = variant("480", 480, "AniLibria", "cdn-b")
        val otherVoice720 = variant("other", 720, "Other", "cdn-c")

        val result = PlaybackAdaptiveQualityPolicy.selectDownshift(
            variants = listOf(current, sameVoice480, otherVoice720, sameVoice720),
            current = current,
            failedVariantKeys = emptySet(),
            blockedHostFamilies = emptySet(),
        )

        assertThat(result).isEqualTo(sameVoice720)
    }

    @Test
    fun `blocked route is skipped during adaptive downshift`() {
        val current = variant("current", 1080, "Voice", "cdn-a")
        val blocked720 = variant("blocked", 720, "Voice", "cdn-b")
        val healthy480 = variant("healthy", 480, "Voice", "cdn-c")

        val result = PlaybackAdaptiveQualityPolicy.selectDownshift(
            variants = listOf(current, blocked720, healthy480),
            current = current,
            failedVariantKeys = emptySet(),
            blockedHostFamilies = setOf("cdn-b"),
        )

        assertThat(result).isEqualTo(healthy480)
    }

    @Test
    fun `healthy same quality mirror prevents premature downshift`() {
        val current = variant("current", 1080, "Voice", "cdn-a")
        val mirror = variant("mirror", 1080, "Voice", "cdn-b")
        val lower = variant("lower", 720, "Voice", "cdn-c")

        assertThat(
            PlaybackAdaptiveQualityPolicy.hasHealthySameQualityAlternative(
                variants = listOf(current, mirror, lower),
                current = current,
                failedVariantKeys = emptySet(),
                blockedHostFamilies = emptySet(),
            ),
        ).isTrue()
    }


    @Test
    fun `automatic downshift remembers recovery quality and stable recovery restores it`() {
        val current = variant("current", 720, "Voice", "cdn-b")
        val preferred = variant("preferred", 1080, "Voice", "cdn-a")
        val state = PlaybackAdaptiveQualityPolicy.markAutomaticDownshift(
            state = PlaybackAdaptiveQualityState(),
            fromQuality = 1080,
            nowMs = 10_000L,
        )

        val result = PlaybackAdaptiveQualityPolicy.selectRecoveryUpshift(
            variants = listOf(current, preferred),
            current = current,
            state = state,
            failedVariantKeys = emptySet(),
            blockedHostFamilies = emptySet(),
        )

        assertThat(result).isEqualTo(preferred)
        val recovered = PlaybackAdaptiveQualityPolicy.markRecoveryUpshift(
            state = state,
            selectedQuality = preferred.quality,
            nowMs = 100_000L,
        )
        assertThat(recovered.recoveryTargetQuality).isNull()
    }

    @Test
    fun `recovery upshift respects route cooldown`() {
        val current = variant("current", 720, "Voice", "cdn-b")
        val preferred = variant("preferred", 1080, "Voice", "cdn-a")
        val state = PlaybackAdaptiveQualityState(recoveryTargetQuality = 1080)

        val result = PlaybackAdaptiveQualityPolicy.selectRecoveryUpshift(
            variants = listOf(current, preferred),
            current = current,
            state = state,
            failedVariantKeys = emptySet(),
            blockedHostFamilies = setOf("cdn-a"),
        )

        assertThat(result).isNull()
    }
    private fun variant(
        key: String,
        quality: Int,
        translation: String,
        host: String,
    ) = PlaybackVariant(
        key = key,
        episodeKey = "episode",
        uri = "https://$host.example/$key.m3u8",
        kind = PlaybackVariantKind.HLS,
        providerId = "provider",
        providerName = "Provider",
        sourceName = "CDN",
        translation = translation,
        translationKey = "voice:$translation",
        quality = quality,
        hostFamily = host,
    )
}
