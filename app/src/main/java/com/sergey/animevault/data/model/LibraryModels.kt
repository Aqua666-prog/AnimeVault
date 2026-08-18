package com.sergey.animevault.data.model

import androidx.room.ColumnInfo
import com.sergey.animevault.data.playback.WatchState

data class LibraryTitleRow(
    val id: Long,
    val name: String,
    val posterUri: String?,
    val dateAdded: Long,
    val episodeCount: Long,
    val completedCount: Long,
    val lastWatchedAt: Long?,
    val onlineLinkCount: Long,
    val totalBytes: Long = 0L,
    val completedBytes: Long = 0L,
    val watchedTimeMs: Long = 0L,
)

/** Latest unfinished local episode for a title, ready for the Home feed. */
data class ContinueWatchingRow(
    val episodeId: Long,
    val titleId: Long,
    val titleName: String,
    val posterUri: String?,
    val episodeNumber: Double?,
    val seasonNumber: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
) {
    val progressFraction: Float
        get() = when {
            positionMs <= 0L || durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

data class GroupingTargetRow(
    val id: Long,
    val sourceKey: String,
    val rootTreeUri: String,
    val name: String,
)

data class OfflineOnlineLinkRow(
    val providerId: String,
    val onlineReleaseId: String,
    val onlineTitleName: String,
    val onlineAlias: String?,
    val posterUrl: String?,
    val malId: String?,
    val kodikId: String?,
    val linkedAt: Long,
)

data class EpisodeRow(
    val id: Long,
    val titleId: Long,
    val fileUri: String,
    val fileName: String,
    val episodeNumber: Double?,
    val seasonNumber: Int?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val mimeType: String?,
    val sortName: String,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastWatchedAt: Long?,
) {
    val watchState: WatchState
        get() = when {
            isCompleted -> WatchState.COMPLETED
            positionMs > 0L -> WatchState.IN_PROGRESS
            else -> WatchState.NOT_STARTED
        }

    val progressFraction: Float
        get() = when {
            isCompleted -> 1f
            durationMs == null || durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

data class PlaybackEpisodeRow(
    val id: Long,
    val titleId: Long,
    val titleName: String,
    val fileUri: String,
    val fileName: String,
    val episodeNumber: Double?,
    val seasonNumber: Int?,
    val durationMs: Long?,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastWatchedAt: Long? = null,
) {
    val watchState: WatchState
        get() = when {
            isCompleted -> WatchState.COMPLETED
            positionMs > 0L -> WatchState.IN_PROGRESS
            else -> WatchState.NOT_STARTED
        }
}

data class SubtitleRow(
    val fileUri: String,
    val fileName: String,
    val mimeType: String,
    val language: String?,
)


data class TitleMetadataRow(
    val titleId: Long,
    val provider: String,
    val externalId: Long,
    val malId: Long?,
    val canonicalTitle: String?,
    val englishTitle: String?,
    val nativeTitle: String?,
    val posterUrl: String?,
    val bannerUrl: String?,
    val description: String?,
    val year: Int?,
    val episodeCount: Int?,
    val format: String?,
    val status: String?,
    val genres: String?,
    val averageScore: Int?,
    val siteUrl: String?,
    val updatedAt: Long,
) {
    val genreList: List<String>
        get() = genres.orEmpty()
            .split("\u001F")
            .map(String::trim)
            .filter(String::isNotBlank)
}
