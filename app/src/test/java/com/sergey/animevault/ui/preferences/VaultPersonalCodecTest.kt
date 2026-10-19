package com.sergey.animevault.ui.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultPersonalCodecTest {
    @Test fun personalRatingsNotesAndSubscriptionsSurviveRoundTrip() {
        val entry = VaultPersonalTitle(rating = 5, note = "Помнить 2 сезон", notifyEpisodes = true,
            translationKey = "voice:1", seenEpisodeIds = listOf("ep1"), baselineReady = true, updatedAt = 123L)
        val data = mapOf("online:animetka:42" to entry)
        assertThat(VaultPersonalCodec.titlesFromJson(VaultPersonalCodec.titlesToJson(data))).isEqualTo(data)
    }

    @Test fun presetsSurviveRoundTripAndMalformedJsonIsSafe() {
        val filter = CatalogAdvancedFilter(genres = setOf("Романтика"), excludedGenres = setOf("Ужасы"),
            genreMode = GenreMatchMode.ALL, yearFrom = 2020, yearTo = 2026, excludeMyLists = true)
        val saved = listOf(CatalogPreset("Моё", "animetka", query = "blue", advanced = filter))
        assertThat(VaultPersonalCodec.presetsFromJson(VaultPersonalCodec.presetsToJson(saved))).isEqualTo(saved)
        assertThat(VaultPersonalCodec.presetsFromJson("{" )).isEmpty()
        assertThat(VaultPersonalCodec.titlesFromJson("{" )).isEmpty()
    }
}
