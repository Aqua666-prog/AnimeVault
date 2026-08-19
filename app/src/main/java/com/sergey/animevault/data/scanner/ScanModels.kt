package com.sergey.animevault.data.scanner

data class DiscoveredSubtitle(
    val fileUri: String,
    val fileName: String,
    val mimeType: String,
    val language: String?,
)

data class DiscoveredEpisode(
    val fileUri: String,
    val fileName: String,
    val episodeNumber: Double?,
    val seasonNumber: Int?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val mimeType: String?,
    val lastModified: Long,
    val sortName: String,
    val subtitles: List<DiscoveredSubtitle>,
)

data class DiscoveredTitle(
    val sourceKey: String,
    val suggestedName: String,
    val posterUri: String?,
    val episodes: List<DiscoveredEpisode>,
)

data class GroupingOverride(
    val sourceKey: String,
    val titleName: String,
)

data class ScanProgress(
    val visitedDocuments: Int,
    val videosFound: Int,
)

data class FolderScanResult(
    val treeUri: String,
    val titles: List<DiscoveredTitle>,
    val visitedDocuments: Int,
    val videosFound: Int,
    val subtitlesFound: Int,
    val warnings: List<String>,
    val autoRecognizedTitles: Int = 0,
)
