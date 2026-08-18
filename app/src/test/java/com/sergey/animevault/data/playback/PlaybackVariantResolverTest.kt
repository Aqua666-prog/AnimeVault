package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackVariantResolverTest {
    @Test
    fun localVariantWinsWhenLocalPreferenceIsEnabled() {
        val remote = variant("remote", PlaybackVariantKind.HLS, quality = 1080)
        val local = variant("local", PlaybackVariantKind.LOCAL)

        val result = PlaybackVariantResolver.selectPreferred(
            variants = listOf(remote, local),
            preference = PlaybackVariantPreference(quality = 1080, preferLocal = true),
        )

        assertThat(result).isEqualTo(local)
    }

    @Test
    fun preferredVoiceWinsInsideOnlineVariants() {
        val other1080 = variant("other", PlaybackVariantKind.HLS, quality = 1080, translation = "Other")
        val preferred720 = variant("preferred", PlaybackVariantKind.HLS, quality = 720, translation = "AniLibria")

        val result = PlaybackVariantResolver.selectPreferred(
            variants = listOf(other1080, preferred720),
            preference = PlaybackVariantPreference(
                translation = "anilibria",
                quality = 1080,
                preferLocal = false,
            ),
        )

        assertThat(result).isEqualTo(preferred720)
    }

    @Test
    fun fallbackKeepsPositionPolicyIndependentAndPrefersClosestVariant() {
        val current = variant("current", PlaybackVariantKind.HLS, quality = 1080, translation = "Voice")
        val same720 = variant("same720", PlaybackVariantKind.HLS, quality = 720, translation = "Voice")
        val other1080 = variant("other1080", PlaybackVariantKind.HLS, quality = 1080, translation = "Other")

        val result = PlaybackVariantResolver.selectFallback(
            variants = listOf(current, other1080, same720),
            current = current,
            failedVariantKeys = setOf(current.key),
            failure = PlaybackFailure(PlaybackFailureKind.TIMEOUT),
        )

        assertThat(result).isEqualTo(same720)
    }

    @Test
    fun authFailureIsSurfacedInsteadOfSilentlySwitchingProvider() {
        val current = variant("current", PlaybackVariantKind.HLS, quality = 1080)
        val fallback = variant("fallback", PlaybackVariantKind.HLS, quality = 720)

        val result = PlaybackVariantResolver.selectFallback(
            variants = listOf(current, fallback),
            current = current,
            failedVariantKeys = setOf(current.key),
            failure = PlaybackFailure(PlaybackFailureKind.AUTH_REQUIRED),
        )

        assertThat(result).isNull()
    }

    private fun variant(
        key: String,
        kind: PlaybackVariantKind,
        quality: Int? = null,
        translation: String? = null,
    ) = PlaybackVariant(
        key = key,
        episodeKey = "episode:1",
        uri = if (kind == PlaybackVariantKind.LOCAL) "content://episode/$key" else "https://example.test/$key.m3u8",
        kind = kind,
        providerId = if (kind == PlaybackVariantKind.LOCAL) null else "provider",
        sourceName = if (kind == PlaybackVariantKind.LOCAL) "Local" else "CDN",
        translation = translation,
        quality = quality,
    )
}
