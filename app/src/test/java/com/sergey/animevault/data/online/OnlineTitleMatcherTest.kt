package com.sergey.animevault.data.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnlineTitleMatcherTest {
    @Test
    fun externalId_beatsDifferentSpelling() {
        val left = card("Атака титанов", "Shingeki no Kyojin", 2013, shiki = 16498)
        val right = card("Вторжение гигантов", null, 2013, shiki = 16498, provider = "other")

        assertThat(OnlineTitleMatcher.score(left, right)).isEqualTo(98)
        assertThat(OnlineTitleMatcher.sameTitle(left, right)).isTrue()
    }

    @Test
    fun normalizedEnglishName_matchesAcrossProviders() {
        val left = card("Реинкарнация безработного 3", "Mushoku Tensei 3rd Season", 2026)
        val right = card("Mushoku Tensei", "Mushoku Tensei Season 3", 2026, provider = "other")

        assertThat(OnlineTitleMatcher.sameTitle(left, right)).isTrue()
    }


    @Test
    fun explicitDifferentSeasons_areNotMergedByStrippedTitle() {
        val left = card("Mushoku Tensei Season 2", null, 2023)
        val right = card("Mushoku Tensei Season 3", null, 2023, provider = "other")

        assertThat(OnlineTitleMatcher.sameTitle(left, right)).isFalse()
    }

    @Test
    fun sameNameFarApartYears_isNotAutoMerged() {
        val left = card("Hunter x Hunter", null, 1999)
        val right = card("Hunter x Hunter", null, 2011, provider = "other")

        assertThat(OnlineTitleMatcher.sameTitle(left, right)).isFalse()
    }

    @Test
    fun conflictingExternalIds_areNeverMergedBySameTitle() {
        val left = card("Same title", "Same title", 2024, shiki = 10)
        val right = card("Same title", "Same title", 2024, shiki = 11, provider = "other")

        assertThat(OnlineTitleMatcher.score(left, right)).isEqualTo(0)
        assertThat(OnlineTitleMatcher.sameTitle(left, right)).isFalse()
    }

    @Test fun textAloneIsNeverConfirmedIdentity() {
        val left = card("Bleach", null, 2004)
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left, left.copy(providerId = "other"))).isFalse()
    }

    @Test fun sharedMalDoesNotOverrideConflictingAnilist() {
        val left = card("Bleach", null, 2004).copy(externalIds = ExternalAnimeIds(malId = 269, anilistId = 1))
        val right = left.copy(providerId = "other", externalIds = ExternalAnimeIds(malId = 269, anilistId = 2))
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left, right)).isFalse()
        assertThat(OnlineTitleMatcher.score(left, right)).isEqualTo(0)
    }

    @Test fun numericalEqualityAcrossNamespacesIsNotIdentity() {
        val left = card("Bleach", null, 2004).copy(externalIds = ExternalAnimeIds(malId = 269))
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left,
            left.copy(providerId = "other", externalIds = ExternalAnimeIds(anilistId = 269)))).isFalse()
    }

    @Test fun sharedIdDoesNotOverrideSeasonFormatOrRemakeConflict() {
        val left = card("Title Season 2", null, 2020, shiki = 10).copy(type = "TV")
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left, left.copy(providerId = "other", name = "Title Season 3"))).isFalse()
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left, left.copy(providerId = "other", type = "Movie"))).isFalse()
        assertThat(OnlineTitleMatcher.confirmedSameTitle(left, left.copy(providerId = "other", year = 1999))).isFalse()
    }

    @Test fun knownSourcesAreNamespacedBoundedAndIndependentOfArrivalOrder() {
        val entries = (1..8).associate { "provider:source$it:release:$it" to listOf("mal:10") } +
            ("provider:wrong:release" to listOf("anilist:10"))
        val first = OnlineTitleMatcher.knownSources(com.sergey.animevault.data.titles.AnimeTitleIndex(references = entries),
            ExternalAnimeIds(malId = 10), "current", "current", { it != "source1" })
        val reversed = OnlineTitleMatcher.knownSources(com.sergey.animevault.data.titles.AnimeTitleIndex(references = entries.entries.reversed().associate { it.toPair() }),
            ExternalAnimeIds(malId = 10), "current", "current", { it != "source1" })
        assertThat(first).hasSize(3)
        assertThat(first).isEqualTo(reversed)
        assertThat(first.map { it.providerId }).doesNotContain("wrong")
        assertThat(first.first().releaseId).isEqualTo("release:2")
    }

    private fun card(
        name: String,
        english: String?,
        year: Int?,
        shiki: Long? = null,
        provider: String = "one",
    ) = OnlineReleaseCard(
        providerId = provider,
        providerName = provider,
        id = "$provider:$name:$year",
        alias = name,
        name = name,
        englishName = english,
        posterUrl = null,
        year = year,
        type = null,
        season = null,
        episodeCount = null,
        isOngoing = false,
        externalIds = ExternalAnimeIds(shikimoriId = shiki),
    )
}
