package com.sergey.animevault.data.model

data class BackupProgressRow(
    val fileUri: String,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastWatchedAt: Long,
)

data class BackupMetadataRow(
    val sourceKey: String,
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
)

data class BackupOnlineLinkRow(
    val sourceKey: String,
    val providerId: String,
    val onlineReleaseId: String,
    val onlineTitleName: String,
    val onlineAlias: String?,
    val posterUrl: String?,
    val malId: String?,
    val kodikId: String?,
    val linkedAt: Long,
)
