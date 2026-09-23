package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AniListMetadataMatcherTest {
    @Test
    fun exactMalId_isVerifiedAndMayAutoApply() {
        val candidate = candidate(
            id = 1,
            malId = 54492,
            title = "Kusuriya no Hitorigoto",
            episodes = 24,
        )

        val match = scoreAniListMetadataCandidate(
            candidate = candidate,
            evidence = MetadataMatchEvidence(
                localTitle = "Монолог фармацевта",
                localEpisodeCount = 24,
                linkedMalIds = setOf(54492),
            ),
        )

        assertThat(match.score).isEqualTo(100)
        assertThat(match.confidence).isEqualTo(MetadataMatchConfidence.VERIFIED)
        assertThat(match.canAutoApply).isTrue()
        assertThat(match.reasons).contains("Совпадает MAL ID")
    }

    @Test
    fun exactOnlineAliasAndEpisodeCount_becomesHighConfidenceButNeedsConfirmation() {
        val match = scoreAniListMetadataCandidate(
            candidate = candidate(
                id = 2,
                title = "Kusuriya no Hitorigoto",
                episodes = 24,
            ),
            evidence = MetadataMatchEvidence(
                localTitle = "Монолог фармацевта",
                onlineAliases = listOf("Kusuriya_no_Hitorigoto"),
                localEpisodeCount = 24,
            ),
        )

        assertThat(match.score).isAtLeast(88)
        assertThat(match.confidence).isEqualTo(MetadataMatchConfidence.HIGH)
        assertThat(match.canAutoApply).isFalse()
        assertThat(match.reasons).contains("Совпадает online alias")
        assertThat(match.reasons).contains("Совпадает число серий")
    }

    @Test
    fun punctuationAndReleaseSeparators_areNormalized() {
        assertThat(normalizeMetadataTitle("Kusuriya_no-Hitorigoto"))
            .isEqualTo("kusuriya no hitorigoto")
        assertThat(normalizeMetadataTitle("  Fate/stay night: UBW  "))
            .isEqualTo("fate stay night ubw")
    }

    @Test
    fun unrelatedCandidate_staysLowConfidence() {
        val match = scoreAniListMetadataCandidate(
            candidate = candidate(id = 3, title = "Sousou no Frieren", episodes = 28),
            evidence = MetadataMatchEvidence(
                localTitle = "Kusuriya no Hitorigoto",
                onlineAliases = listOf("Kusuriya no Hitorigoto"),
                localEpisodeCount = 24,
            ),
        )

        assertThat(match.confidence).isEqualTo(MetadataMatchConfidence.LOW)
        assertThat(match.canAutoApply).isFalse()
    }

    @Test
    fun rankingPrefersMatchingAliasOverAniListSearchOrder() {
        val unrelated = candidate(id = 10, title = "Another", episodes = 12)
        val correct = candidate(id = 11, title = "Steins;Gate", episodes = 24)

        val ranked = rankAniListMetadataCandidates(
            candidates = listOf(unrelated, correct),
            evidence = MetadataMatchEvidence(
                localTitle = "Врата Штейна",
                onlineAliases = listOf("Steins Gate"),
                localEpisodeCount = 24,
            ),
        )

        assertThat(ranked.first().candidate.anilistId).isEqualTo(11)
        assertThat(ranked.first().confidence).isEqualTo(MetadataMatchConfidence.HIGH)
    }

    @Test
    fun episodeMismatchPenalizesOtherwiseSimilarCandidate() {
        val evidence = MetadataMatchEvidence(
            localTitle = "Example Show",
            onlineAliases = listOf("Example Show"),
            localEpisodeCount = 24,
        )
        val exactEpisodes = scoreAniListMetadataCandidate(
            candidate(20, title = "Example Show", episodes = 24),
            evidence,
        )
        val wrongEpisodes = scoreAniListMetadataCandidate(
            candidate(21, title = "Example Show", episodes = 8),
            evidence,
        )

        assertThat(exactEpisodes.score).isGreaterThan(wrongEpisodes.score)
        assertThat(wrongEpisodes.confidence).isEqualTo(MetadataMatchConfidence.MEDIUM)
    }

    private fun candidate(
        id: Long,
        malId: Long? = null,
        title: String,
        episodes: Int? = null,
    ) = AniListMetadataCandidate(
        anilistId = id,
        malId = malId,
        canonicalTitle = title,
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
        averageScore = 80,
        siteUrl = null,
    )
}
