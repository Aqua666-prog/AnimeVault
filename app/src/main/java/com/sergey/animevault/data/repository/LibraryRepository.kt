package com.sergey.animevault.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.sergey.animevault.data.db.AnimeTitleEntity
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.db.EpisodeEntity
import com.sergey.animevault.data.db.EpisodeGroupingOverrideEntity
import com.sergey.animevault.data.db.ExternalSubtitleEntity
import com.sergey.animevault.data.db.LibraryFolderEntity
import com.sergey.animevault.data.db.OfflineOnlineLinkEntity
import com.sergey.animevault.data.db.WatchProgressEntity
import com.sergey.animevault.data.db.TitleMetadataEntity
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.model.ContinueWatchingRow
import com.sergey.animevault.data.model.GroupingTargetRow
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.model.OfflineOnlineLinkRow
import com.sergey.animevault.data.model.PlaybackEpisodeRow
import com.sergey.animevault.data.model.SubtitleRow
import com.sergey.animevault.data.model.TitleMetadataRow
import com.sergey.animevault.data.scanner.FolderScanResult
import com.sergey.animevault.data.metadata.AniListMetadataCandidate
import com.sergey.animevault.data.scanner.LibraryScanner
import com.sergey.animevault.data.scanner.GroupingOverride
import com.sergey.animevault.data.scanner.ScanProgress
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalAniListSyncTarget(
    val anilistId: Long,
    val watchedEpisode: Int,
    val episodeCount: Int?,
)

data class PlaybackBundle(
    val episode: PlaybackEpisodeRow,
    val subtitles: List<SubtitleRow>,
    val nextEpisodeId: Long?,
)

data class StorageSummary(
    val totalBytes: Long = 0L,
    val reclaimableBytes: Long = 0L,
    val completedFiles: Long = 0L,
)

data class StorageCleanupResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val failedFiles: Int,
    val foldersNeedingWriteAccess: Set<String>,
)

internal fun summarizeStorage(titles: List<LibraryTitleRow>): StorageSummary = StorageSummary(
    totalBytes = titles.sumOf { it.totalBytes.coerceAtLeast(0L) },
    reclaimableBytes = titles.sumOf { it.completedBytes.coerceAtLeast(0L) },
    completedFiles = titles.sumOf { it.completedCount.coerceAtLeast(0L) },
)

class LibraryRepository(
    private val context: Context,
    private val database: AnimeVaultDatabase,
    private val scanner: LibraryScanner,
) {
    private val dao = database.libraryDao()
    private val scanMutex = Mutex()

    fun observeLibrary(): Flow<List<LibraryTitleRow>> = dao.observeLibrary()

    fun observeHomeContinueWatching(): Flow<List<ContinueWatchingRow>> =
        dao.observeHomeContinueWatching()

    fun observeFolders(): Flow<List<LibraryFolderEntity>> = dao.observeFolders()

    fun observeGroupingTargets(): Flow<List<GroupingTargetRow>> = dao.observeGroupingTargets()

    fun observeTitle(titleId: Long): Flow<AnimeTitleEntity?> = dao.observeTitle(titleId)

    fun observeEpisodes(titleId: Long): Flow<List<EpisodeRow>> = dao.observeEpisodes(titleId)

    fun observeTitleMetadata(titleId: Long): Flow<TitleMetadataRow?> =
        dao.observeTitleMetadata(titleId)

    fun observeOnlineLinks(titleId: Long): Flow<List<OfflineOnlineLinkRow>> =
        dao.observeOfflineOnlineLinks(titleId)

    suspend fun addFolderAndScan(
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit = {},
    ): FolderScanResult {
        persistTreePermission(treeUri)
        val now = System.currentTimeMillis()
        val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?.takeIf(String::isNotBlank)
            ?: "Видеотека"
        val existing = dao.getFolders().firstOrNull { it.treeUri == treeUri.toString() }
        dao.upsertFolder(
            LibraryFolderEntity(
                treeUri = treeUri.toString(),
                displayName = displayName,
                addedAt = existing?.addedAt ?: now,
                lastScannedAt = existing?.lastScannedAt,
            ),
        )
        return scanFolder(treeUri, onProgress)
    }

    suspend fun scanFolder(
        treeUri: Uri,
        onProgress: (ScanProgress) -> Unit = {},
    ): FolderScanResult = scanMutex.withLock {
        val overrides = dao.getGroupingOverridesInFolder(treeUri.toString()).associate { override ->
            override.fileUri to GroupingOverride(
                sourceKey = override.targetSourceKey,
                titleName = override.targetTitleName,
            )
        }
        val result = scanner.scan(treeUri, overrides, onProgress)
        val timestamp = System.currentTimeMillis()

        database.withTransaction {
            val previousTitles = dao.getTitlesInFolder(result.treeUri)
            val previousEpisodesByUri = dao.getEpisodeEntitiesInFolder(result.treeUri)
                .associateBy(EpisodeEntity::fileUri)
            val keptTitleIds = mutableSetOf<Long>()

            // Subtitles are rebuilt after episodes may move between title groups.
            previousTitles.forEach { dao.deleteSubtitlesForTitle(it.id) }

            result.titles.forEach { discoveredTitle ->
                val previousTitle = dao.getTitleBySourceKey(discoveredTitle.sourceKey)
                val titleId = if (previousTitle == null) {
                    dao.insertTitle(
                        AnimeTitleEntity(
                            sourceKey = discoveredTitle.sourceKey,
                            rootTreeUri = result.treeUri,
                            name = discoveredTitle.suggestedName,
                            posterUri = discoveredTitle.posterUri,
                            dateAdded = timestamp,
                            lastScannedAt = timestamp,
                        ),
                    )
                } else {
                    dao.updateTitle(
                        previousTitle.copy(
                            name = if (previousTitle.isNameUserEdited) {
                                previousTitle.name
                            } else {
                                discoveredTitle.suggestedName
                            },
                            posterUri = previousTitle.posterUri ?: discoveredTitle.posterUri,
                            lastScannedAt = timestamp,
                        ),
                    )
                    previousTitle.id
                }
                keptTitleIds += titleId

                val episodeEntities = discoveredTitle.episodes.map { discovered ->
                    val previous = previousEpisodesByUri[discovered.fileUri]
                    EpisodeEntity(
                        id = previous?.id ?: 0,
                        titleId = titleId,
                        fileUri = discovered.fileUri,
                        fileName = discovered.fileName,
                        episodeNumber = discovered.episodeNumber,
                        seasonNumber = discovered.seasonNumber,
                        durationMs = previous?.durationMs ?: discovered.durationMs,
                        sizeBytes = discovered.sizeBytes,
                        mimeType = discovered.mimeType,
                        lastModified = discovered.lastModified,
                        sortName = discovered.sortName,
                    )
                }

                if (episodeEntities.isEmpty()) {
                    dao.deleteEpisodesForTitle(titleId)
                } else {
                    dao.upsertEpisodes(episodeEntities)
                    dao.deleteEpisodesNotIn(titleId, episodeEntities.map { it.fileUri })
                }

                val savedEpisodes = dao.getEpisodeEntities(titleId).associateBy { it.fileUri }
                val subtitleEntities = discoveredTitle.episodes.flatMap { discovered ->
                    val episodeId = savedEpisodes[discovered.fileUri]?.id ?: return@flatMap emptyList()
                    discovered.subtitles.map { subtitle ->
                        ExternalSubtitleEntity(
                            episodeId = episodeId,
                            fileUri = subtitle.fileUri,
                            fileName = subtitle.fileName,
                            mimeType = subtitle.mimeType,
                            language = subtitle.language,
                        )
                    }
                }
                if (subtitleEntities.isNotEmpty()) {
                    dao.insertSubtitles(subtitleEntities)
                }
            }

            val removedTitleIds = previousTitles.map { it.id }.filterNot(keptTitleIds::contains)
            if (removedTitleIds.isNotEmpty()) {
                dao.deleteTitles(removedTitleIds)
            }
            val keptFileUris = result.titles.flatMap { title -> title.episodes.map { it.fileUri } }
            if (keptFileUris.isEmpty()) {
                dao.deleteGroupingOverridesInFolder(result.treeUri)
            } else {
                dao.deleteStaleGroupingOverrides(result.treeUri, keptFileUris)
            }
            dao.updateFolderScanTime(result.treeUri, timestamp)
        }
        result
    }

    suspend fun scanAllFolders(
        onFolderStarted: (LibraryFolderEntity) -> Unit = {},
        onProgress: (ScanProgress) -> Unit = {},
    ): List<FolderScanResult> = dao.getFolders().map { folder ->
        onFolderStarted(folder)
        scanFolder(folder.treeUri.toUri(), onProgress)
    }

    suspend fun removeFolder(treeUri: String) {
        database.withTransaction { dao.deleteFolder(treeUri) }
        runCatching {
            val permission = context.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri.toString() == treeUri }
            var flags = 0
            if (permission?.isReadPermission == true) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission?.isWritePermission == true) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) {
                context.contentResolver.releasePersistableUriPermission(treeUri.toUri(), flags)
            }
        }
    }

    suspend fun getPlaybackBundle(episodeId: Long): PlaybackBundle? {
        val episode = dao.getPlaybackEpisode(episodeId) ?: return null
        val ordered = dao.getEpisodeEntities(episode.titleId).sortedWith(episodeComparator)
        val currentIndex = ordered.indexOfFirst { it.id == episodeId }
        val nextId = ordered.getOrNull(currentIndex + 1)?.id
        return PlaybackBundle(
            episode = episode,
            subtitles = dao.getSubtitles(episodeId),
            nextEpisodeId = nextId,
        )
    }

    suspend fun savePlaybackProgress(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): Boolean {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val completed = ended || (
            safeDuration > 0L &&
                safePosition >= (safeDuration * COMPLETION_THRESHOLD).toLong()
            )
        database.withTransaction {
            if (safeDuration > 0L) {
                dao.updateEpisodeDuration(episodeId, safeDuration)
            }
            dao.upsertProgress(
                WatchProgressEntity(
                    episodeId = episodeId,
                    positionMs = if (completed) 0L else safePosition,
                    isCompleted = completed,
                    lastWatchedAt = System.currentTimeMillis(),
                ),
            )
        }
        return completed
    }

    suspend fun getAniListSyncTarget(episodeId: Long): LocalAniListSyncTarget? {
        val playback = dao.getPlaybackEpisode(episodeId) ?: return null
        val metadata = dao.getTitleMetadataEntity(playback.titleId) ?: return null
        if (metadata.provider != "anilist" || metadata.externalId <= 0L) return null
        val episodes = dao.getEpisodeEntities(playback.titleId).sortedWith(episodeComparator)
        val index = episodes.indexOfFirst { it.id == episodeId }
        val watchedEpisode = playback.episodeNumber
            ?.takeIf { it > 0.0 }
            ?.toInt()
            ?.takeIf { it > 0 }
            ?: (index + 1).takeIf { index >= 0 }
            ?: return null
        return LocalAniListSyncTarget(
            anilistId = metadata.externalId,
            watchedEpisode = watchedEpisode,
            episodeCount = metadata.episodeCount ?: episodes.size.takeIf { it > 0 },
        )
    }

    suspend fun setTitlePoster(titleId: Long, posterUri: Uri) {
        runCatching { persistReadPermission(posterUri) }
        dao.updateTitlePoster(titleId, posterUri.toString())
    }

    suspend fun saveAniListMetadata(titleId: Long, candidate: AniListMetadataCandidate) {
        requireNotNull(dao.getTitle(titleId)) { "Локальный тайтл не найден" }
        dao.upsertTitleMetadata(
            TitleMetadataEntity(
                titleId = titleId,
                provider = "anilist",
                externalId = candidate.anilistId,
                malId = candidate.malId,
                canonicalTitle = candidate.canonicalTitle,
                englishTitle = candidate.englishTitle,
                nativeTitle = candidate.nativeTitle,
                posterUrl = candidate.posterUrl,
                bannerUrl = candidate.bannerUrl,
                description = candidate.description,
                year = candidate.year,
                episodeCount = candidate.episodeCount,
                format = candidate.format,
                status = candidate.status,
                genres = candidate.genres.joinToString(TitleMetadataEntity.GENRE_SEPARATOR),
                averageScore = candidate.averageScore,
                siteUrl = candidate.siteUrl,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteCompletedVideoFiles(): StorageCleanupResult {
        val episodes = dao.getCompletedEpisodeEntities()
        var deletedFiles = 0
        var deletedBytes = 0L
        var failedFiles = 0
        val missingWrite = linkedSetOf<String>()

        for (episode in episodes) {
            val title = dao.getTitle(episode.titleId)
            val treeUri = title?.rootTreeUri
            if (treeUri == null || !hasPersistedWritePermission(treeUri)) {
                failedFiles += 1
                treeUri?.let(missingWrite::add)
                continue
            }
            val deleted = runCatching {
                DocumentFile.fromSingleUri(context, episode.fileUri.toUri())?.delete() == true
            }.getOrDefault(false)
            if (deleted) {
                database.withTransaction { dao.deleteEpisodeById(episode.id) }
                deletedFiles += 1
                deletedBytes += episode.sizeBytes.coerceAtLeast(0L)
            } else {
                failedFiles += 1
            }
        }
        return StorageCleanupResult(
            deletedFiles = deletedFiles,
            deletedBytes = deletedBytes,
            failedFiles = failedFiles,
            foldersNeedingWriteAccess = missingWrite,
        )
    }

    private fun hasPersistedWritePermission(treeUri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == treeUri && permission.isWritePermission
        }

    suspend fun clearTitleMetadata(titleId: Long) {
        dao.deleteTitleMetadata(titleId)
    }

    suspend fun mergeEpisodesIntoTitle(episodeIds: Set<Long>, targetTitleId: Long) {
        require(episodeIds.isNotEmpty()) { "Не выбраны серии для объединения" }
        database.withTransaction {
            val target = dao.getTitle(targetTitleId) ?: error("Тайтл назначения не найден")
            val episodes = dao.getEpisodesByIds(episodeIds.toList())
            require(episodes.size == episodeIds.size) { "Часть выбранных серий больше недоступна" }
            val sourceTitles = episodes.map(EpisodeEntity::titleId)
                .distinct()
                .mapNotNull { dao.getTitle(it) }
            require(sourceTitles.all { it.rootTreeUri == target.rootTreeUri }) {
                "Объединять можно только файлы из одной корневой папки"
            }
            val now = System.currentTimeMillis()
            dao.upsertGroupingOverrides(
                episodes.map { episode ->
                    EpisodeGroupingOverrideEntity(
                        fileUri = episode.fileUri,
                        rootTreeUri = target.rootTreeUri,
                        targetSourceKey = target.sourceKey,
                        targetTitleName = target.name,
                        createdAt = now,
                    )
                },
            )
            dao.upsertEpisodes(episodes.map { it.copy(titleId = target.id) })
            sourceTitles.filter { it.id != target.id }.forEach { sourceTitle ->
                if (dao.getEpisodeEntities(sourceTitle.id).isEmpty()) {
                    // Связи относятся ко всему тайтлу. Переносим их только тогда,
                    // когда после объединения исходная карточка действительно опустела.
                    dao.getOfflineOnlineLinkEntities(sourceTitle.id).forEach { link ->
                        dao.upsertOfflineOnlineLink(link.copy(offlineTitleId = target.id))
                    }
                    if (dao.getTitleMetadataEntity(target.id) == null) {
                        dao.getTitleMetadataEntity(sourceTitle.id)?.let { metadata ->
                            dao.upsertTitleMetadata(metadata.copy(titleId = target.id))
                        }
                    }
                    dao.deleteTitle(sourceTitle.id)
                }
            }
        }
    }

    suspend fun separateEpisodes(episodeIds: Set<Long>, newTitleName: String): Long {
        require(episodeIds.isNotEmpty()) { "Не выбраны серии для отделения" }
        val cleanName = newTitleName.trim().takeIf(String::isNotBlank)
            ?: error("Введите название нового тайтла")
        return database.withTransaction {
            val episodes = dao.getEpisodesByIds(episodeIds.toList())
            require(episodes.size == episodeIds.size) { "Часть выбранных серий больше недоступна" }
            val sourceTitles = episodes.map(EpisodeEntity::titleId)
                .distinct()
                .mapNotNull { dao.getTitle(it) }
            val rootUri = sourceTitles.firstOrNull()?.rootTreeUri ?: error("Корневая папка не найдена")
            require(sourceTitles.all { it.rootTreeUri == rootUri }) {
                "Отделять вместе можно только файлы из одной корневой папки"
            }
            val now = System.currentTimeMillis()
            val sourceKey = "$rootUri::manual:${UUID.randomUUID()}"
            val titleId = dao.insertTitle(
                AnimeTitleEntity(
                    sourceKey = sourceKey,
                    rootTreeUri = rootUri,
                    name = cleanName,
                    isNameUserEdited = true,
                    dateAdded = now,
                    lastScannedAt = now,
                ),
            )
            dao.upsertGroupingOverrides(
                episodes.map { episode ->
                    EpisodeGroupingOverrideEntity(
                        fileUri = episode.fileUri,
                        rootTreeUri = rootUri,
                        targetSourceKey = sourceKey,
                        targetTitleName = cleanName,
                        createdAt = now,
                    )
                },
            )
            dao.upsertEpisodes(episodes.map { it.copy(titleId = titleId) })
            sourceTitles.forEach { sourceTitle ->
                if (dao.getEpisodeEntities(sourceTitle.id).isEmpty()) dao.deleteTitle(sourceTitle.id)
            }
            titleId
        }
    }

    suspend fun restoreAutomaticGrouping(episodeIds: Set<Long>) {
        if (episodeIds.isEmpty()) return
        database.withTransaction {
            val episodes = dao.getEpisodesByIds(episodeIds.toList())
            dao.deleteGroupingOverrides(episodes.map(EpisodeEntity::fileUri))
        }
        scanAllFolders()
    }

    suspend fun linkOnlineTitle(titleId: Long, release: OnlineReleaseCard) {
        requireNotNull(dao.getTitle(titleId)) { "Локальный тайтл не найден" }
        val reference = release.id.substringAfter(':', "")
        dao.upsertOfflineOnlineLink(
            OfflineOnlineLinkEntity(
                offlineTitleId = titleId,
                providerId = release.providerId,
                onlineReleaseId = release.id,
                onlineTitleName = release.name,
                onlineAlias = release.alias,
                posterUrl = release.posterUrl,
                malId = reference.takeIf {
                    release.providerId == OnlineProviderIds.KODIK && release.id.startsWith("shiki:")
                },
                kodikId = reference.takeIf {
                    release.providerId == OnlineProviderIds.KODIK && release.id.startsWith("kodik:")
                },
                linkedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unlinkOnlineTitle(titleId: Long, providerId: String, releaseId: String) {
        dao.deleteOfflineOnlineLink(titleId, providerId, releaseId)
    }

    suspend fun clearProgress() = database.withTransaction { dao.clearProgress() }

    suspend fun clearLibrary() {
        val folders = dao.getFolders()
        database.withTransaction { dao.clearLibrary() }
        folders.forEach { folder ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    folder.treeUri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun persistTreePermission(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, read or write)
        }.getOrElse {
            context.contentResolver.takePersistableUriPermission(uri, read)
        }
    }

    private fun persistReadPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private companion object {
        const val COMPLETION_THRESHOLD = 0.92

        val episodeComparator = compareBy<EpisodeEntity>(
            { it.seasonNumber == null },
            { it.seasonNumber ?: Int.MAX_VALUE },
            { it.episodeNumber == null },
            { it.episodeNumber ?: Double.MAX_VALUE },
            { it.sortName.lowercase() },
        )
    }
}
