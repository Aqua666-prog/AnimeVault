package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineTranslationOptionsTest {
    @Test
    fun `translation options aggregate quality and episode coverage`() {
        val details = release(
            episode(
                "e1",
                stream("voice-1", "Dream Cast", "Озвучка", 720),
                stream("sub-1", "Crunchyroll.Subtitles", "Субтитры", 1080),
            ),
            episode(
                "e2",
                stream("voice-2", "Dream Cast", "Озвучка", 1080),
            ),
        )

        val options = details.translationOptions()

        assertThat(options).hasSize(2)
        val voice = options.first { it.name == "Dream Cast" }
        assertThat(voice.episodeCount).isEqualTo(2)
        assertThat(voice.quality).isEqualTo(1080)
        assertThat(voice.qualityLabel).isEqualTo("FHD")
        assertThat(voice.isSubtitles).isFalse()

        val subtitles = options.first { it.name == "Crunchyroll.Subtitles" }
        assertThat(subtitles.episodeCount).isEqualTo(1)
        assertThat(subtitles.qualityLabel).isEqualTo("FHD")
        assertThat(subtitles.isSubtitles).isTrue()
    }

    private fun release(vararg episodes: OnlineEpisode) = OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK,
        providerName = "Kodik",
        id = "shiki:1",
        alias = "1",
        name = "Test",
        englishName = null,
        posterUrl = null,
        year = 2026,
        type = "TV-сериал",
        season = "Сезон 1",
        episodeCount = episodes.size,
        description = null,
        notification = null,
        genres = emptyList(),
        isOngoing = true,
        isBlocked = false,
        episodes = episodes.toList(),
    )

    private fun episode(id: String, vararg streams: OnlineStream) = OnlineEpisode(
        providerId = OnlineProviderIds.KODIK,
        id = id,
        releaseId = "shiki:1",
        ordinal = id.removePrefix("e").toDoubleOrNull(),
        name = id,
        previewUrl = null,
        durationMs = 24 * 60_000L,
        sortOrder = id.removePrefix("e").toDoubleOrNull(),
        streams = streams.toList(),
    )

    private fun stream(
        id: String,
        translation: String,
        sourceName: String,
        quality: Int,
    ) = OnlineStream(
        id = id,
        quality = quality,
        url = "https://example.test/$id",
        type = OnlineStreamType.EMBED,
        translation = translation,
        sourceName = sourceName,
    )
}
