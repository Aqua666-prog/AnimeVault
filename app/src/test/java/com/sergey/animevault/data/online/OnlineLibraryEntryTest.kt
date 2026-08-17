package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineLibraryEntryTest {
    @Test
    fun `partial episode is offered for continue watching`() {
        val entry = entry(
            lastEpisodeId = "episode-4",
            lastPositionMs = 300_000L,
            lastDurationMs = 1_200_000L,
        )

        assertThat(entry.hasContinueProgress).isTrue()
        assertThat(entry.progressFraction).isWithin(0.001f).of(0.25f)
    }

    @Test
    fun `completed episode is not offered for continue watching`() {
        val entry = entry(
            lastEpisodeId = "episode-4",
            lastPositionMs = 0L,
            lastDurationMs = 1_200_000L,
            lastEpisodeCompleted = true,
        )

        assertThat(entry.hasContinueProgress).isFalse()
        assertThat(entry.progressFraction).isEqualTo(1f)
    }

    @Test
    fun `saved entry can be reopened as release card`() {
        val card = entry(isFavorite = true).toReleaseCard()

        assertThat(card.providerId).isEqualTo("test")
        assertThat(card.id).isEqualTo("release-1")
        assertThat(card.name).isEqualTo("Тестовый релиз")
        assertThat(card.posterUrl).isEqualTo("https://example/poster.webp")
    }

    private fun entry(
        isFavorite: Boolean = false,
        lastEpisodeId: String? = null,
        lastPositionMs: Long = 0L,
        lastDurationMs: Long = 0L,
        lastEpisodeCompleted: Boolean = false,
    ) = OnlineLibraryEntry(
        providerId = "test",
        providerName = "Тест",
        releaseId = "release-1",
        name = "Тестовый релиз",
        posterUrl = "https://example/poster.webp",
        isFavorite = isFavorite,
        lastOpenedAt = 1L,
        lastEpisodeId = lastEpisodeId,
        lastPositionMs = lastPositionMs,
        lastDurationMs = lastDurationMs,
        lastEpisodeCompleted = lastEpisodeCompleted,
    )
}
