package com.sergey.animevault.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeFeedTest {
    @Test
    fun `continue items are ordered newest first and limited`() {
        val items = (1L..20L).map { index ->
            HomeContinueItem.Local(
                episodeId = index,
                titleId = index,
                title = "Title $index",
                posterUri = null,
                episodeNumber = index.toDouble(),
                seasonNumber = 1,
                positionMs = 10_000L,
                durationMs = 20_000L,
                lastWatchedAt = index,
            )
        }

        val ranked = rankContinueItems(items, limit = 5)

        assertThat(ranked.map(HomeContinueItem::lastWatchedAt))
            .containsExactly(20L, 19L, 18L, 17L, 16L)
            .inOrder()
    }

    @Test
    fun `items without watch timestamp are excluded`() {
        val invalid = HomeContinueItem.Online(
            providerId = "provider",
            releaseId = "release",
            episodeId = "episode",
            title = "Example",
            posterUri = null,
            episodeOrdinal = 2.0,
            providerName = "Provider",
            positionMs = 10_000L,
            durationMs = 20_000L,
            lastWatchedAt = 0L,
        )

        assertThat(rankContinueItems(listOf(invalid))).isEmpty()
    }

    @Test
    fun `progress is clamped and safe for unknown duration`() {
        assertThat(progressFraction(30_000L, 20_000L)).isEqualTo(1f)
        assertThat(progressFraction(5_000L, 20_000L)).isEqualTo(0.25f)
        assertThat(progressFraction(5_000L, 0L)).isEqualTo(0f)
    }
}
