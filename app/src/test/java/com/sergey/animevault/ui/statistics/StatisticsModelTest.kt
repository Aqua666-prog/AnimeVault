package com.sergey.animevault.ui.statistics

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.model.LocalHistoryRow
import com.sergey.animevault.data.model.TitleMetadataRow
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StatisticsModelTest {
    @Test
    fun snapshotBuildsTotalsGenresAndActivity() {
        val now = 1_700_000_000_000L
        val titles = listOf(
            LibraryTitleRow(1, "A", null, now, 12, 3, now, 0, 0, 0, 180 * 60_000L),
        )
        val history = listOf(
            LocalHistoryRow(1, 1, "A", null, 1.0, 1, 1, 2, true, now),
        )
        val metadata = listOf(
            TitleMetadataRow(1, "anilist", 1, null, "A", null, null, null, null, null, 2024, 12, "TV", null, "Fantasy\u001FDrama", 82, null, now),
        )
        val snapshot = buildStatisticsSnapshot(titles, history, metadata, emptyList(), now)
        assertThat(snapshot.completedEpisodeCount).isEqualTo(3)
        assertThat(snapshot.topGenres.map(GenreStat::name)).contains("Fantasy")
        assertThat(snapshot.activeDays).isEqualTo(1)
        assertThat(snapshot.averageAniListScore).isEqualTo(82)
    }
    @Test
    fun activityUsesCalendarDaysAcrossDst() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val calendar = Calendar.getInstance().apply {
                set(2026, Calendar.MARCH, 30, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val days = buildActivityDays(
                timestamps = listOf(calendar.timeInMillis),
                nowMs = calendar.timeInMillis,
                days = 4,
            )
            assertThat(days).hasSize(4)
            assertThat(days.last().count).isEqualTo(1)
            val dates = days.map { day ->
                Calendar.getInstance().apply { timeInMillis = day.dayStartMs }.get(Calendar.DAY_OF_MONTH)
            }
            assertThat(dates).containsExactly(27, 28, 29, 30).inOrder()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

}
