package com.sergey.animevault.data.scanner

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.metadata.AniListMetadataCandidate
import org.junit.Test

class LocalTitleRecognizerTest {
    @Test
    fun `season suffix is removed from AniList query`() {
        val title = discovered("Frieren — сезон 2", 12)
        assertThat(LocalTitleRecognizer.queryFor(title)).isEqualTo("Frieren")
    }

    @Test
    fun `exact strong candidate is auto selected`() {
        val title = discovered("Sousou no Frieren", 28)
        val best = candidate(1, "Sousou no Frieren", 28)
        val other = candidate(2, "Frieren Beyond Journey's End Special", 4)

        assertThat(LocalTitleRecognizer.autoCandidate(title, listOf(best, other))).isEqualTo(best)
    }

    @Test
    fun `ambiguous exact candidates are not auto selected`() {
        val title = discovered("Monster", 74)
        val a = candidate(1, "Monster", 74)
        val b = candidate(2, "Monster", 74)

        assertThat(LocalTitleRecognizer.autoCandidate(title, listOf(a, b))).isNull()
    }

    @Test
    fun `larger local episode count blocks wrong remote season`() {
        val title = discovered("Example", 24)
        val wrong = candidate(1, "Example", 12)

        assertThat(LocalTitleRecognizer.autoCandidate(title, listOf(wrong))).isNull()
    }

    private fun discovered(name: String, count: Int) = DiscoveredTitle(
        sourceKey = "key:$name",
        suggestedName = name,
        posterUri = null,
        episodes = (1..count).map { episode ->
            DiscoveredEpisode(
                fileUri = "file:///$episode.mkv",
                fileName = "$name - $episode.mkv",
                episodeNumber = episode.toDouble(),
                seasonNumber = null,
                durationMs = null,
                sizeBytes = 1,
                mimeType = "video/x-matroska",
                lastModified = 0,
                sortName = episode.toString(),
                subtitles = emptyList(),
            )
        },
    )

    private fun candidate(id: Long, name: String, episodes: Int) = AniListMetadataCandidate(
        anilistId = id,
        malId = null,
        canonicalTitle = name,
        englishTitle = null,
        nativeTitle = null,
        synonyms = emptyList(),
        posterUrl = null,
        bannerUrl = null,
        accentHex = null,
        description = null,
        year = 2024,
        episodeCount = episodes,
        format = "TV",
        status = "FINISHED",
        genres = emptyList(),
        averageScore = null,
        siteUrl = null,
    )
}
