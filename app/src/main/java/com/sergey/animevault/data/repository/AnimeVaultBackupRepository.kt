package com.sergey.animevault.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.sergey.animevault.data.db.AnimeVaultDatabase
import com.sergey.animevault.data.db.EpisodeGroupingOverrideEntity
import com.sergey.animevault.data.db.OfflineOnlineLinkEntity
import com.sergey.animevault.data.db.TitleMetadataEntity
import com.sergey.animevault.data.db.WatchProgressEntity
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineWatchProgress
import java.io.IOException

data class AnimeVaultBackup(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val createdAt: Long,
    val progress: List<BackupProgress> = emptyList(),
    val metadata: List<BackupMetadata> = emptyList(),
    val onlineLinks: List<BackupOnlineLink> = emptyList(),
    val groupingOverrides: List<BackupGroupingOverride> = emptyList(),
    val onlineLibrary: List<OnlineLibraryEntry> = emptyList(),
    val onlineProgress: Map<String, OnlineWatchProgress> = emptyMap(),
)

data class BackupProgress(
    val fileUri: String,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastWatchedAt: Long,
)

data class BackupMetadata(
    val sourceKey: String,
    val provider: String,
    val externalId: Long,
    val malId: Long? = null,
    val canonicalTitle: String? = null,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val episodeCount: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: String? = null,
    val averageScore: Int? = null,
    val siteUrl: String? = null,
    val updatedAt: Long,
)

data class BackupOnlineLink(
    val sourceKey: String,
    val providerId: String,
    val onlineReleaseId: String,
    val onlineTitleName: String,
    val onlineAlias: String? = null,
    val posterUrl: String? = null,
    val malId: String? = null,
    val kodikId: String? = null,
    val linkedAt: Long,
)

data class BackupGroupingOverride(
    val fileUri: String,
    val rootTreeUri: String,
    val targetSourceKey: String,
    val targetTitleName: String,
    val createdAt: Long,
)

data class BackupRestoreResult(
    val progressRestored: Int,
    val metadataRestored: Int,
    val linksRestored: Int,
    val groupingOverridesRestored: Int,
    val onlineLibraryRestored: Int,
    val onlineProgressRestored: Int,
    val skipped: Int,
)

internal object AnimeVaultBackupCodec {
    private val gson = Gson()

    fun encode(backup: AnimeVaultBackup): String = gson.toJson(backup)

    fun decode(json: String): AnimeVaultBackup {
        val backup = runCatching { gson.fromJson(json, AnimeVaultBackup::class.java) }
            .getOrElse { throw JsonParseException("Повреждённый файл резервной копии", it) }
            ?: throw JsonParseException("Пустая резервная копия")
        require(backup.formatVersion in 1..BACKUP_FORMAT_VERSION) {
            "Версия резервной копии ${backup.formatVersion} новее поддерживаемой $BACKUP_FORMAT_VERSION"
        }
        return backup
    }
}

class AnimeVaultBackupRepository(
    private val context: Context,
    private val database: AnimeVaultDatabase,
    private val onlineRepository: OnlineRepository,
) {
    private val dao = database.libraryDao()

    suspend fun exportTo(uri: Uri): AnimeVaultBackup {
        val backup = createSnapshot()
        val output = context.contentResolver.openOutputStream(uri, "rwt")
            ?: throw IOException("Не удалось открыть файл для записи")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(AnimeVaultBackupCodec.encode(backup))
        }
        return backup
    }

    suspend fun importFrom(uri: Uri): BackupRestoreResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть резервную копию")
        val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return restore(AnimeVaultBackupCodec.decode(json))
    }

    suspend fun createSnapshot(): AnimeVaultBackup = database.withTransaction {
        AnimeVaultBackup(
            createdAt = System.currentTimeMillis(),
            progress = dao.getBackupProgressRows().map { row ->
                BackupProgress(row.fileUri, row.positionMs, row.isCompleted, row.lastWatchedAt)
            },
            metadata = dao.getBackupMetadataRows().map { row ->
                BackupMetadata(
                    sourceKey = row.sourceKey,
                    provider = row.provider,
                    externalId = row.externalId,
                    malId = row.malId,
                    canonicalTitle = row.canonicalTitle,
                    englishTitle = row.englishTitle,
                    nativeTitle = row.nativeTitle,
                    posterUrl = row.posterUrl,
                    bannerUrl = row.bannerUrl,
                    description = row.description,
                    year = row.year,
                    episodeCount = row.episodeCount,
                    format = row.format,
                    status = row.status,
                    genres = row.genres,
                    averageScore = row.averageScore,
                    siteUrl = row.siteUrl,
                    updatedAt = row.updatedAt,
                )
            },
            onlineLinks = dao.getBackupOnlineLinkRows().map { row ->
                BackupOnlineLink(
                    sourceKey = row.sourceKey,
                    providerId = row.providerId,
                    onlineReleaseId = row.onlineReleaseId,
                    onlineTitleName = row.onlineTitleName,
                    onlineAlias = row.onlineAlias,
                    posterUrl = row.posterUrl,
                    malId = row.malId,
                    kodikId = row.kodikId,
                    linkedAt = row.linkedAt,
                )
            },
            groupingOverrides = dao.getAllGroupingOverrideEntities().map { row ->
                BackupGroupingOverride(
                    row.fileUri,
                    row.rootTreeUri,
                    row.targetSourceKey,
                    row.targetTitleName,
                    row.createdAt,
                )
            },
            onlineLibrary = onlineRepository.snapshotLibraryEntries(),
            onlineProgress = onlineRepository.snapshotOnlineProgress(),
        )
    }

    suspend fun restore(backup: AnimeVaultBackup): BackupRestoreResult = database.withTransaction {
        val titles = dao.getAllTitleEntities().associateBy { it.sourceKey }
        val episodes = dao.getAllEpisodeEntities().associateBy { it.fileUri }
        val folders = dao.getFolders().associateBy { it.treeUri }
        var progressRestored = 0
        var metadataRestored = 0
        var linksRestored = 0
        var overridesRestored = 0
        var skipped = 0

        backup.progress.forEach { item ->
            val episode = episodes[item.fileUri]
            if (episode == null) {
                skipped++
            } else {
                dao.upsertProgress(
                    WatchProgressEntity(
                        episodeId = episode.id,
                        positionMs = item.positionMs.coerceAtLeast(0L),
                        isCompleted = item.isCompleted,
                        lastWatchedAt = item.lastWatchedAt.coerceAtLeast(0L),
                    ),
                )
                progressRestored++
            }
        }

        backup.metadata.forEach { item ->
            val title = titles[item.sourceKey]
            if (title == null) {
                skipped++
            } else {
                dao.upsertTitleMetadata(
                    TitleMetadataEntity(
                        titleId = title.id,
                        provider = item.provider,
                        externalId = item.externalId,
                        malId = item.malId,
                        canonicalTitle = item.canonicalTitle,
                        englishTitle = item.englishTitle,
                        nativeTitle = item.nativeTitle,
                        posterUrl = item.posterUrl,
                        bannerUrl = item.bannerUrl,
                        description = item.description,
                        year = item.year,
                        episodeCount = item.episodeCount,
                        format = item.format,
                        status = item.status,
                        genres = item.genres,
                        averageScore = item.averageScore,
                        siteUrl = item.siteUrl,
                        updatedAt = item.updatedAt,
                    ),
                )
                metadataRestored++
            }
        }

        backup.onlineLinks.forEach { item ->
            val title = titles[item.sourceKey]
            if (title == null) {
                skipped++
            } else {
                dao.upsertOfflineOnlineLink(
                    OfflineOnlineLinkEntity(
                        offlineTitleId = title.id,
                        providerId = item.providerId,
                        onlineReleaseId = item.onlineReleaseId,
                        onlineTitleName = item.onlineTitleName,
                        onlineAlias = item.onlineAlias,
                        posterUrl = item.posterUrl,
                        malId = item.malId,
                        kodikId = item.kodikId,
                        linkedAt = item.linkedAt,
                    ),
                )
                linksRestored++
            }
        }

        backup.groupingOverrides.forEach { item ->
            if (folders[item.rootTreeUri] == null) {
                skipped++
            } else {
                dao.upsertGroupingOverrides(
                    listOf(
                        EpisodeGroupingOverrideEntity(
                            fileUri = item.fileUri,
                            rootTreeUri = item.rootTreeUri,
                            targetSourceKey = item.targetSourceKey,
                            targetTitleName = item.targetTitleName,
                            createdAt = item.createdAt,
                        ),
                    ),
                )
                overridesRestored++
            }
        }

        onlineRepository.restoreOnlineState(backup.onlineLibrary, backup.onlineProgress)
        BackupRestoreResult(
            progressRestored = progressRestored,
            metadataRestored = metadataRestored,
            linksRestored = linksRestored,
            groupingOverridesRestored = overridesRestored,
            onlineLibraryRestored = backup.onlineLibrary.size,
            onlineProgressRestored = backup.onlineProgress.size,
            skipped = skipped,
        )
    }
}

internal const val BACKUP_FORMAT_VERSION = 1
