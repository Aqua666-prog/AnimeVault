package com.sergey.animevault.ui.downloads

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.data.online.OnlineStreamType
import org.junit.Test

class DownloadsPresentationTest {
    @Test
    fun `overview separates active ready and failed downloads`() {
        val entries = listOf(
            entry("queued", DownloadStatus.QUEUED),
            entry("paused", DownloadStatus.PAUSED),
            entry("ready", DownloadStatus.COMPLETED, bytes = 2_048L),
            entry("failed", DownloadStatus.FAILED, bytes = 9_999L),
        )

        val overview = summarizeDownloads(entries)

        assertThat(overview.totalCount).isEqualTo(4)
        assertThat(overview.activeCount).isEqualTo(2)
        assertThat(overview.readyCount).isEqualTo(1)
        assertThat(overview.errorCount).isEqualTo(1)
        assertThat(overview.storedBytes).isEqualTo(2_048L)
    }

    @Test
    fun `filters keep status groups independent`() {
        val entries = listOf(
            entry("downloading", DownloadStatus.DOWNLOADING),
            entry("ready", DownloadStatus.COMPLETED),
            entry("failed", DownloadStatus.FAILED),
        )

        assertThat(filterDownloads(entries, DownloadFilter.ACTIVE).map(DownloadEntry::id))
            .containsExactly("downloading")
        assertThat(filterDownloads(entries, DownloadFilter.READY).map(DownloadEntry::id))
            .containsExactly("ready")
        assertThat(filterDownloads(entries, DownloadFilter.ERRORS).map(DownloadEntry::id))
            .containsExactly("failed")
    }

    @Test
    fun `byte formatter stays compact`() {
        assertThat(formatBytes(512L)).isEqualTo("512 Б")
        assertThat(formatBytes(1_536L)).isEqualTo("1.5 КБ")
        assertThat(formatBytes(2L * 1024L * 1024L)).isEqualTo("2 МБ")
    }

    @Test
    fun `missing files are recoverable errors and are included in overview`() {
        val missing = entry("missing", DownloadStatus.MISSING)
        assertThat(summarizeDownloads(listOf(missing)).errorCount).isEqualTo(1)
        assertThat(filterDownloads(listOf(missing), DownloadFilter.ERRORS)).containsExactly(missing)
    }

    @Test
    fun `summary separates working waiting paused and removal without losing legacy active filter`() {
        val entries = DownloadStatus.entries.map { entry(it.name, it) }
        val overview = summarizeDownloads(entries)
        assertThat(overview.runningCount).isEqualTo(3)
        assertThat(overview.waitingCount).isEqualTo(2)
        assertThat(overview.pausedCount).isEqualTo(1)
        assertThat(overview.readyCount).isEqualTo(1)
        assertThat(overview.errorCount).isEqualTo(2)
        assertThat(filterDownloads(entries, DownloadFilter.ACTIVE).map { it.status })
            .contains(DownloadStatus.REMOVING)
    }

    @Test
    fun `unknown and invalid progress do not become measured zero percent`() {
        val download = entry("download", DownloadStatus.DOWNLOADING)
        assertThat(hasMeasuredDownloadProgress(download)).isFalse()
        assertThat(hasMeasuredDownloadProgress(download.copy(contentLength = 100L))).isTrue()
        assertThat(hasMeasuredDownloadProgress(download.copy(totalItems = 20))).isTrue()
        assertThat(hasMeasuredDownloadProgress(download.copy(progressPercent = 50f))).isTrue()
        assertThat(hasMeasuredDownloadProgress(download.copy(progressPercent = Float.NaN))).isFalse()
        assertThat(hasMeasuredDownloadProgress(download.copy(progressPercent = 150f))).isFalse()
    }

    private fun entry(
        id: String,
        status: DownloadStatus,
        bytes: Long = 0L,
    ) = DownloadEntry(
        id = id,
        providerId = "provider",
        providerName = "Provider",
        releaseId = "release",
        releaseName = "Release",
        episodeId = "episode-$id",
        episodeOrdinal = 1.0,
        episodeName = null,
        quality = 720,
        translation = null,
        translationKey = null,
        sourceName = "source",
        streamType = OnlineStreamType.HLS,
        status = status,
        bytesDownloaded = bytes,
    )
}
