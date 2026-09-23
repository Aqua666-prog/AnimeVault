package com.sergey.animevault.ui.statistics

import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.model.LocalHistoryRow
import com.sergey.animevault.data.model.TitleMetadataRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

data class GenreStat(
    val name: String,
    val count: Int,
)

data class ActivityDay(
    val dayStartMs: Long,
    val count: Int,
)

data class StatisticsSnapshot(
    val localTitleCount: Int = 0,
    val localEpisodeCount: Long = 0L,
    val completedEpisodeCount: Long = 0L,
    val watchedTimeMs: Long = 0L,
    val onlineHistoryCount: Int = 0,
    val onlineFavoriteCount: Int = 0,
    val onlineTrackedTimeMs: Long = 0L,
    val averageAniListScore: Int? = null,
    val averageCompletedEpisodeMinutes: Int? = null,
    val topGenres: List<GenreStat> = emptyList(),
    val activity: List<ActivityDay> = emptyList(),
    val activeDays: Int = 0,
)

internal fun buildStatisticsSnapshot(
    titles: List<LibraryTitleRow>,
    localHistory: List<LocalHistoryRow>,
    metadata: List<TitleMetadataRow>,
    onlineEntries: Collection<OnlineLibraryEntry>,
    nowMs: Long = System.currentTimeMillis(),
): StatisticsSnapshot {
    val completed = titles.sumOf { it.completedCount.coerceAtLeast(0L) }
    val watchedTime = titles.sumOf { it.watchedTimeMs.coerceAtLeast(0L) }
    val onlineHistory = onlineEntries.filter(OnlineLibraryEntry::hasHistory)
    val scores = metadata.mapNotNull { it.averageScore?.takeIf { score -> score > 0 } }
    val genreCounts = metadata
        .flatMap(TitleMetadataRow::genreList)
        .map(String::trim)
        .filter(String::isNotBlank)
        .groupingBy { it.lowercase(Locale.ROOT) }
        .eachCount()
    val displayNames = metadata
        .flatMap(TitleMetadataRow::genreList)
        .associateBy { it.lowercase(Locale.ROOT) }

    val activityTimestamps = buildList {
        addAll(localHistory.map(LocalHistoryRow::lastWatchedAt).filter { it > 0L })
        addAll(onlineHistory.map(OnlineLibraryEntry::lastWatchedAt).filter { it > 0L })
    }
    val activity = buildActivityDays(activityTimestamps, nowMs = nowMs, days = 28)

    return StatisticsSnapshot(
        localTitleCount = titles.size,
        localEpisodeCount = titles.sumOf { it.episodeCount.coerceAtLeast(0L) },
        completedEpisodeCount = completed,
        watchedTimeMs = watchedTime,
        onlineHistoryCount = onlineHistory.size,
        onlineFavoriteCount = onlineEntries.count(OnlineLibraryEntry::isFavorite),
        onlineTrackedTimeMs = onlineHistory.sumOf { entry ->
            if (entry.lastEpisodeCompleted) entry.lastDurationMs.coerceAtLeast(0L)
            else entry.lastPositionMs.coerceAtLeast(0L)
        },
        averageAniListScore = scores.takeIf(List<Int>::isNotEmpty)?.average()?.roundToInt(),
        averageCompletedEpisodeMinutes = completed.takeIf { it > 0L }
            ?.let { (watchedTime / it / 60_000L).toInt().coerceAtLeast(0) },
        topGenres = genreCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .map { (key, count) -> GenreStat(displayNames[key] ?: key, count) },
        activity = activity,
        activeDays = activity.count { it.count > 0 },
    )
}

internal fun buildActivityDays(
    timestamps: List<Long>,
    nowMs: Long,
    days: Int,
): List<ActivityDay> {
    if (days <= 0) return emptyList()
    val end = startOfLocalDay(nowMs)
    val cursor = Calendar.getInstance().apply {
        timeInMillis = end
        add(Calendar.DAY_OF_YEAR, -(days - 1))
    }
    val dayStarts = buildList(days) {
        repeat(days) {
            add(cursor.timeInMillis)
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    val first = dayStarts.first()
    val endExclusive = Calendar.getInstance().apply {
        timeInMillis = end
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    val counts = timestamps
        .asSequence()
        .filter { it >= first && it < endExclusive }
        .groupingBy(::startOfLocalDay)
        .eachCount()
    return dayStarts.map { day -> ActivityDay(dayStartMs = day, count = counts[day] ?: 0) }
}

private fun startOfLocalDay(timestamp: Long): Long = Calendar.getInstance().run {
    timeInMillis = timestamp
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}
