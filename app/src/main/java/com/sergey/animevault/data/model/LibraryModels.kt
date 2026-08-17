package com.sergey.animevault.data.model

import androidx.room.ColumnInfo

data class LibraryTitleRow(
    val id: Long,
    val name: String,
    val posterUri: String?,
    val dateAdded: Long,
    val episodeCount: Long,
    val completedCount: Long,
    val lastWatchedAt: Long?,
    val onlineLinkCount: Long,
)

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
)

data class SubtitleRow(
    val fileUri: String,
    val fileName: String,
    val mimeType: String,
    val language: String?,
)
