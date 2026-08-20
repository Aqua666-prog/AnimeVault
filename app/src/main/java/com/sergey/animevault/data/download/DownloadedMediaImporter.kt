package com.sergey.animevault.data.download

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.withTransaction
import com.sergey.animevault.data.db.AnimeTitleEntity
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.db.EpisodeEntity
import com.sergey.animevault.data.db.LibraryFolderEntity
import com.sergey.animevault.data.db.OfflineOnlineLinkEntity
import java.io.File

/** Closes the native-download -> Room library -> offline-player chain. */
class DownloadedMediaImporter(
    private val database: AnimeVaultDatabase,
) {
    private val dao = database.libraryDao()

    suspend fun import(entry: DownloadEntry, result: NativeDownloadResult): Long {
        val file = result.file
        require(file.isFile && file.length() > 0L) { "Загруженный файл отсутствует" }
        val now = System.currentTimeMillis()
        val fileUri = Uri.fromFile(file).toString()
        val durationMs = readDuration(file)
        val sourceKey = sourceKey(entry)

        return database.withTransaction {
            dao.upsertFolder(
                LibraryFolderEntity(
                    treeUri = DOWNLOADS_TREE_URI,
                    displayName = "Загрузки AnimeVault",
                    addedAt = now,
                    lastScannedAt = now,
                ),
            )
            val previousTitle = dao.getTitleBySourceKey(sourceKey)
            val titleId = if (previousTitle == null) {
                dao.insertTitle(
                    AnimeTitleEntity(
                        sourceKey = sourceKey,
                        rootTreeUri = DOWNLOADS_TREE_URI,
                        name = entry.releaseName,
                        dateAdded = now,
                        lastScannedAt = now,
                    ),
                )
            } else {
                dao.updateTitle(previousTitle.copy(lastScannedAt = now))
                previousTitle.id
            }

            val previousEpisodes = dao.getEpisodeEntities(titleId)
            val previous = previousEpisodes.firstOrNull { episode ->
                episode.fileUri == fileUri || episode.fileName.startsWith("${entry.id}.")
            }
            val extension = file.extension.ifBlank { if (result.mimeType == "video/mp2t") "ts" else "mp4" }
            dao.upsertEpisodes(
                listOf(
                    EpisodeEntity(
                        id = previous?.id ?: 0L,
                        titleId = titleId,
                        fileUri = fileUri,
                        fileName = "${entry.id}.$extension",
                        episodeNumber = entry.episodeOrdinal,
                        seasonNumber = null,
                        durationMs = durationMs ?: previous?.durationMs,
                        sizeBytes = file.length(),
                        mimeType = result.mimeType,
                        lastModified = file.lastModified().coerceAtLeast(now),
                        sortName = entry.episodeLabel.lowercase(),
                    ),
                ),
            )
            previousEpisodes
                .filter { it.id != previous?.id && it.fileName.startsWith("${entry.id}.") }
                .forEach { dao.deleteEpisodeById(it.id) }
            dao.upsertOfflineOnlineLink(
                OfflineOnlineLinkEntity(
                    offlineTitleId = titleId,
                    providerId = entry.providerId,
                    onlineReleaseId = entry.releaseId,
                    onlineTitleName = entry.releaseName,
                    linkedAt = now,
                ),
            )
            dao.getEpisodeEntities(titleId).first { it.fileUri == fileUri }.id
        }
    }

    suspend fun remove(entry: DownloadEntry) {
        val path = entry.localFilePath ?: return
        val uri = Uri.fromFile(File(path)).toString()
        database.withTransaction {
            val title = dao.getTitleBySourceKey(sourceKey(entry)) ?: return@withTransaction
            dao.getEpisodeEntities(title.id)
                .filter { it.fileUri == uri || it.fileName.startsWith("${entry.id}.") }
                .forEach { dao.deleteEpisodeById(it.id) }
            if (dao.getEpisodeEntities(title.id).isEmpty()) dao.deleteTitle(title.id)
        }
    }

    private fun readDuration(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun sourceKey(entry: DownloadEntry): String =
        "animevault-download::${entry.providerId}::${entry.releaseId}"

    private companion object {
        const val DOWNLOADS_TREE_URI = "animevault://downloads"
    }
}
