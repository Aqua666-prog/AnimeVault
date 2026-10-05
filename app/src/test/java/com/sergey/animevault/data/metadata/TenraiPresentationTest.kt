package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineEpisode
import java.util.Calendar
import org.junit.Test

class TenraiPresentationTest {
    @Test
    fun matchEpisode_matchesOnlyIntegerOrdinal() {
        val metadata = listOf(
            TenraiEpisodeMetadata(1, "One", null, null, null, null, false, false, null),
            TenraiEpisodeMetadata(2, "Two", null, null, null, null, true, false, null),
        )
        assertThat(matchTenraiEpisode(episode(2.0), metadata)?.title).isEqualTo("Two")
        assertThat(matchTenraiEpisode(episode(2.5), metadata)).isNull()
    }

    @Test
    fun airDate_formatsIsoDate() {
        assertThat(formatTenraiAirDate("2026-10-06T18:30:00+00:00")).isEqualTo("06.10.2026")
        assertThat(formatTenraiAirDate(null)).isNull()
    }

    @Test
    fun scheduleFilter_mapsCalendarDays() {
        assertThat(tenraiScheduleFilter(Calendar.MONDAY)).isEqualTo("monday")
        assertThat(tenraiScheduleFilter(Calendar.SUNDAY)).isEqualTo("sunday")
    }

    private fun episode(ordinal: Double) = OnlineEpisode(
        providerId = "test",
        id = "ep-$ordinal",
        releaseId = "release",
        ordinal = ordinal,
        name = null,
        previewUrl = null,
        durationMs = 0L,
        sortOrder = ordinal,
        streams = emptyList(),
    )
}
