package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.playback.PlaybackFailure
import com.sergey.animevault.data.playback.PlaybackFailureKind
import org.junit.Test

class OnlinePlayerRefreshTest {
    @Test
    fun `expired Animetka stream is eligible for one-shot refresh`() {
        val stream = animetkaStream("https://cdn.example.invalid/video.m3u8?token=old")

        assertThat(
            shouldRefreshExpiredAnimetkaStream(
                PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
                stream,
            ),
        ).isTrue()
        assertThat(
            shouldRefreshExpiredAnimetkaStream(
                PlaybackFailure(PlaybackFailureKind.NOT_FOUND, httpCode = 410),
                stream,
            ),
        ).isTrue()
    }

    @Test
    fun `refresh identity ignores changing signed query`() {
        val old = animetkaStream("https://cdn.example.invalid/video.m3u8?token=old&expires=1")
        val fresh = animetkaStream("https://cdn.example.invalid/video.m3u8?token=new&expires=2")

        assertThat(old.streamRefreshIdentity()).isEqualTo(fresh.streamRefreshIdentity())
    }

    @Test
    fun `refresh helper is provider neutral for refreshable signed streams`() {
        val kodik = animetkaStream("https://cdn.example.invalid/video.m3u8?token=old")
            .copy(providerId = "kodik")

        assertThat(
            shouldRefreshExpiredOnlineStream(
                PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
                kodik,
            ),
        ).isTrue()
    }

    @Test
    fun `stream close to explicit expiry refreshes after transient failure`() {
        val stream = animetkaStream("https://cdn.example.invalid/video.m3u8?token=old")
            .copy(
                providerId = "custom",
                expiresAtEpochMs = 1_050_000L,
            )

        assertThat(
            shouldRefreshExpiredOnlineStream(
                failure = PlaybackFailure(PlaybackFailureKind.TIMEOUT),
                stream = stream,
                nowMs = 1_000_000L,
            ),
        ).isTrue()
    }

    @Test
    fun `non refreshable stream never re-resolves`() {
        val stream = animetkaStream("https://cdn.example.invalid/video.m3u8")
            .copy(refreshable = false)

        assertThat(
            shouldRefreshExpiredOnlineStream(
                PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
                stream,
            ),
        ).isFalse()
    }

    @Test
    fun `other providers and non-expiry failures never trigger Animetka refresh`() {
        val other = animetkaStream("https://cdn.example.invalid/video.m3u8").copy(providerId = "kodik")
        val animetka = animetkaStream("https://cdn.example.invalid/video.m3u8")

        assertThat(
            shouldRefreshExpiredAnimetkaStream(
                PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
                other,
            ),
        ).isFalse()
        assertThat(
            shouldRefreshExpiredAnimetkaStream(
                PlaybackFailure(PlaybackFailureKind.TIMEOUT),
                animetka,
            ),
        ).isFalse()
        assertThat(
            shouldRefreshExpiredAnimetkaStream(
                PlaybackFailure(PlaybackFailureKind.FORBIDDEN, httpCode = 403),
                animetka.copy(refreshable = false),
            ),
        ).isFalse()
    }

    private fun animetkaStream(url: String) = OnlineStream(
        id = "stable-stream",
        quality = 720,
        url = url,
        type = OnlineStreamType.HLS,
        translation = "AniLibria",
        sourceName = "cdn-a",
        translationId = "610",
        providerId = OnlineProviderIds.ANIMETKA,
        providerName = "Аниметка",
        hostFamily = "cdn-a",
        refreshable = true,
    )
}
