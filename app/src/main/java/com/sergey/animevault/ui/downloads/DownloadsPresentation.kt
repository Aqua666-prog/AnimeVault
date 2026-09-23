package com.sergey.animevault.ui.downloads

import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus

internal enum class DownloadFilter(val title: String) {
    ALL("Все"),
    ACTIVE("В процессе"),
    READY("Готово"),
    ERRORS("Ошибки"),
}

internal data class DownloadOverview(
    val totalCount: Int,
    val activeCount: Int,
    val readyCount: Int,
    val errorCount: Int,
    val storedBytes: Long,
)

internal fun summarizeDownloads(entries: List<DownloadEntry>): DownloadOverview = DownloadOverview(
    totalCount = entries.size,
    activeCount = entries.count { entry -> entry.isActive || entry.status == DownloadStatus.PAUSED || entry.status == DownloadStatus.REMOVING },
    readyCount = entries.count { it.status == DownloadStatus.COMPLETED },
    errorCount = entries.count { it.status == DownloadStatus.FAILED },
    storedBytes = entries
        .asSequence()
        .filter { it.status == DownloadStatus.COMPLETED }
        .sumOf { entry -> entry.bytesDownloaded.coerceAtLeast(0L) },
)

internal fun filterDownloads(
    entries: List<DownloadEntry>,
    filter: DownloadFilter,
): List<DownloadEntry> = when (filter) {
    DownloadFilter.ALL -> entries
    DownloadFilter.ACTIVE -> entries.filter { entry ->
        entry.isActive || entry.status == DownloadStatus.PAUSED || entry.status == DownloadStatus.REMOVING
    }
    DownloadFilter.READY -> entries.filter { it.status == DownloadStatus.COMPLETED }
    DownloadFilter.ERRORS -> entries.filter { it.status == DownloadStatus.FAILED }
}

internal fun DownloadOverview.countFor(filter: DownloadFilter): Int = when (filter) {
    DownloadFilter.ALL -> totalCount
    DownloadFilter.ACTIVE -> activeCount
    DownloadFilter.READY -> readyCount
    DownloadFilter.ERRORS -> errorCount
}
