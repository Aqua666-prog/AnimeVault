package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.ui.calendar.calendarProviderSearchTitle
import com.sergey.animevault.ui.calendar.filterCalendarByCountry
import org.junit.Test

class AiringCountryFilterTest {
    @Test fun separatesJapanChinaAndUnknownWithoutDroppingUnknownFromAll() {
        val jp = VaultAiringEpisode(1, "Japanese", 1, 1000, null, countryCode = "JP")
        val cn = VaultAiringEpisode(2, "Chinese", 1, 1000, null, countryCode = "CN")
        val unknown = VaultAiringEpisode(3, "Other", 1, 1000, null)
        val entries = listOf(jp, cn, unknown)
        assertThat(filterCalendarByCountry(entries, null)).containsExactlyElementsIn(entries).inOrder()
        assertThat(filterCalendarByCountry(entries, "JP")).containsExactly(jp)
        assertThat(filterCalendarByCountry(entries, "CN")).containsExactly(cn)
    }

    @Test fun translatedTitlesCanBeSearchedWhileMissingOnesCannot() {
        val jp = VaultAiringEpisode(11, "Стальной алхимик", 2, 1000, null,
            searchTitle = "Fullmetal Alchemist")
        assertThat(calendarProviderSearchTitle(jp)).isEqualTo("Стальной алхимик")
        val cn = VaultAiringEpisode(12, "Douluo Dalu", 2, 1000, null,
            searchTitle = "Douluo Dalu")
        assertThat(calendarProviderSearchTitle(cn)).isEqualTo("Douluo Dalu")
        val unknown = VaultAiringEpisode(13, "Без названия · AniList #13", 2, 1000, null,
            searchTitle = "")
        assertThat(calendarProviderSearchTitle(unknown)).isNull()
    }
}
