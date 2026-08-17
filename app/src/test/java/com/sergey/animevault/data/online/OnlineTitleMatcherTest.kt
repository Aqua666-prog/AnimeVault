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
