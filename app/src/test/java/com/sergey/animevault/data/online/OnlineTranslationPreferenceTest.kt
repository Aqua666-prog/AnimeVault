package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineTranslationPreferenceTest {
    @Test
    fun `translation options collapse the same variant across episodes`() {
        val voice = stream(id = "voice-1", translation = "FumoDub", kind = "Озвучка")
        val subtitles = stream(id = "sub-1", translation = "CR", kind = "Субтитры")
        val release = release(
            episodes = listOf(
                episode("1", listOf(voice, subtitles)),
                episode("2", listOf(voice.copy(id = "voice-2"), subtitles.copy(id = "sub-2"))),
            ),
        )

        assertThat(release.translationOptions().map(OnlineTranslationOption::displayName))
            .containsExactly("FumoDub · Озвучка", "CR · Субтитры")
            .inOrder()
    }

    @Test
    fun `preference distinguishes voice from subtitles with the same name`() {
        val voice = stream(id = "voice", translation = "Studio", kind = "Озвучка")
        val subtitles = stream(id = "subtitles", translation = "Studio", kind = "Субтитры")

        assertThat(voice.translationPreferenceKey).isNotEqualTo(subtitles.translationPreferenceKey)
    }

    @Test
    fun `preferred translation moves to the front and missing preference keeps fallback`() {
        val voice = stream(id = "voice", translation = "FumoDub", kind = "Озвучка")
        val subtitles = stream(id = "subtitles", translation = "CR", kind = "Субтитры")
        val original = listOf(voice, subtitles)

        assertThat(original.prioritizeTranslation(subtitles.translationPreferenceKey))
            .containsExactly(subtitles, voice)
            .inOrder()
        assertThat(original.prioritizeTranslation("missing"))
            .containsExactlyElementsIn(original)
            .inOrder()
    }


    @Test
    fun `playback preference keeps voice ahead of quality and restores quality inside it`() {
        val dub720 = stream(id = "dub-720", translation = "FumoDub", kind = "Озвучка", quality = 720)
        val dub1080 = stream(id = "dub-1080", translation = "FumoDub", kind = "Озвучка", quality = 1080)
        val sub1080 = stream(id = "sub-1080", translation = "CR", kind = "Субтитры", quality = 1080)
        val original = listOf(sub1080, dub720, dub1080)

        assertThat(
            original.prioritizePlaybackPreferences(
                preferredTranslationKey = dub720.translationPreferenceKey,
                preferredQuality = 1080,
            ),
        ).containsExactly(dub1080, dub720, sub1080).inOrder()
    }

    @Test
    fun `quality preference works even when stream has no translation`() {
        val auto720 = stream(id = "auto-720", translation = "", kind = "", quality = 720)
        val auto1080 = stream(id = "auto-1080", translation = "", kind = "", quality = 1080)

        assertThat(
            listOf(auto720, auto1080).prioritizePlaybackPreferences(null, 1080),
        ).containsExactly(auto1080, auto720).inOrder()
    }

    private fun stream(
        id: String,
        translation: String,
        kind: String,
        quality: Int? = null,
    ) = OnlineStream(
        id = id,
        quality = quality,
        url = "https://example.com/$id",
        type = OnlineStreamType.EMBED,
        translation = translation,
        sourceName = kind,
    )

    private fun episode(id: String, streams: List<OnlineStream>) = OnlineEpisode(
        providerId = OnlineProviderIds.KODIK,
        id = id,
        releaseId = "release",
        ordinal = id.toDoubleOrNull(),
        name = null,
        previewUrl = null,
        durationMs = 24 * 60_000L,
        sortOrder = id.toDoubleOrNull(),
        streams = streams,
    )

    private fun release(episodes: List<OnlineEpisode>) = OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK,
        providerName = "Kodik",
        id = "release",
        alias = "release",
        name = "Тест",
        englishName = null,
        posterUrl = null,
        year = null,
        type = null,
        season = null,
        episodeCount = episodes.size,
        description = null,
        notification = null,
        genres = emptyList(),
        isOngoing = false,
        isBlocked = false,
        episodes = episodes,
    )
}
