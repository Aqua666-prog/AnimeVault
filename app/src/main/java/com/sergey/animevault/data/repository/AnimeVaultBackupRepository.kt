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
import com.sergey.animevault.BuildConfig
import java.io.IOException

data class AnimeVaultBackup(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val createdAt: Long,
    val appVersion: String? = null,
    val databaseVersion: Int = 0,
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
    val firstPlayedAt: Long = 0L,
    val completedAt: Long? = null,
    val playCount: Int = 0,
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

enum class BackupMergePolicy {
    NEWER_WINS,
    BACKUP_WINS,
    CURRENT_WINS,
}

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

internal fun <T : Any> selectByTimestamp(
    current: T?,
    incoming: T,
    currentTimestamp: Long,
    incomingTimestamp: Long,
    policy: BackupMergePolicy,
): T = when (policy) {
    BackupMergePolicy.BACKUP_WINS -> incoming
    BackupMergePolicy.CURRENT_WINS -> current ?: incoming
    BackupMergePolicy.NEWER_WINS -> if (current == null || incomingTimestamp >= currentTimestamp) incoming else current
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

    suspend fun importFrom(
        uri: Uri,
        mergePolicy: BackupMergePolicy = BackupMergePolicy.NEWER_WINS,
    ): BackupRestoreResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть резервную копию")
        val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return restore(AnimeVaultBackupCodec.decode(json), mergePolicy)
    }

    suspend fun createSnapshot(): AnimeVaultBackup = database.withTransaction {
        AnimeVaultBackup(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            databaseVersion = 4,
            progress = dao.getBackupProgressRows().map { row ->
                BackupProgress(
                    fileUri = row.fileUri,
                    positionMs = row.positionMs,
                    isCompleted = row.isCompleted,
                    lastWatchedAt = row.lastWatchedAt,
                    firstPlayedAt = row.firstPlayedAt,
                    completedAt = row.completedAt,
                    playCount = row.playCount,
                )
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

    suspend fun restore(
        backup: AnimeVaultBackup,
        mergePolicy: BackupMergePolicy = BackupMergePolicy.NEWER_WINS,
    ): BackupRestoreResult = database.withTransaction {
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
                val incoming = WatchProgressEntity(
                    episodeId = episode.id,
                    positionMs = item.positionMs.coerceAtLeast(0L),
                    isCompleted = item.isCompleted,
                    lastWatchedAt = item.lastWatchedAt.coerceAtLeast(0L),
                    firstPlayedAt = item.firstPlayedAt.coerceAtLeast(0L)
                        .takeIf { it > 0L } ?: item.lastWatchedAt.coerceAtLeast(0L),
                    completedAt = item.completedAt?.coerceAtLeast(0L)
                        ?: item.lastWatchedAt.takeIf { item.isCompleted && it > 0L },
                    playCount = item.playCount.coerceAtLeast(0)
                        .takeIf { it > 0 } ?: if (item.lastWatchedAt > 0L) 1 else 0,
                )
                val current = dao.getProgressEntity(episode.id)
                val selected = selectByTimestamp(
                    current = current,
                    incoming = incoming,
                    currentTimestamp = current?.lastWatchedAt ?: Long.MIN_VALUE,
                    incomingTimestamp = incoming.lastWatchedAt,
                    policy = mergePolicy,
                )
                if (selected === current) {
                    skipped++
                } else {
                    dao.upsertProgress(incoming)
                    progressRestored++
                }
            }
        }

        backup.metadata.forEach { item ->
            val title = titles[item.sourceKey]
            if (title == null) {
                skipped++
            } else {
                val incoming = TitleMetadataEntity(
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
                )
                val current = dao.getTitleMetadataEntity(title.id)
                val selected = selectByTimestamp(
                    current = current,
                    incoming = incoming,
                    currentTimestamp = current?.updatedAt ?: Long.MIN_VALUE,
                    incomingTimestamp = incoming.updatedAt,
                    policy = mergePolicy,
                )
                if (selected === current) {
                    skipped++
                } else {
                    dao.upsertTitleMetadata(incoming)
                    metadataRestored++
                }
            }
        }

        backup.onlineLinks.forEach { item ->
            val title = titles[item.sourceKey]
            if (title == null) {
                skipped++
            } else {
                val incoming = OfflineOnlineLinkEntity(
                    offlineTitleId = title.id,
                    providerId = item.providerId,
                    onlineReleaseId = item.onlineReleaseId,
                    onlineTitleName = item.onlineTitleName,
                    onlineAlias = item.onlineAlias,
                    posterUrl = item.posterUrl,
                    malId = item.malId,
                    kodikId = item.kodikId,
                    linkedAt = item.linkedAt,
                )
                val current = dao.getOfflineOnlineLink(title.id, item.providerId, item.onlineReleaseId)
                val selected = selectByTimestamp(
                    current = current,
                    incoming = incoming,
                    currentTimestamp = current?.linkedAt ?: Long.MIN_VALUE,
                    incomingTimestamp = incoming.linkedAt,
                    policy = mergePolicy,
                )
                if (selected === current) {
                    skipped++
                } else {
                    dao.upsertOfflineOnlineLink(incoming)
                    linksRestored++
                }
            }
        }

        backup.groupingOverrides.forEach { item ->
            if (folders[item.rootTreeUri] == null) {
                skipped++
            } else {
                val incoming = EpisodeGroupingOverrideEntity(
                    fileUri = item.fileUri,
                    rootTreeUri = item.rootTreeUri,
                    targetSourceKey = item.targetSourceKey,
                    targetTitleName = item.targetTitleName,
                    createdAt = item.createdAt,
                )
                val current = dao.getGroupingOverride(item.fileUri)
                val selected = selectByTimestamp(
                    current = current,
                    incoming = incoming,
                    currentTimestamp = current?.createdAt ?: Long.MIN_VALUE,
                    incomingTimestamp = incoming.createdAt,
                    policy = mergePolicy,
                )
                if (selected === current) {
                    skipped++
                } else {
                    dao.upsertGroupingOverrides(listOf(incoming))
                    overridesRestored++
                }
            }
        }

        val currentOnlineLibrary = onlineRepository.snapshotLibraryEntries()
            .associateBy { "${it.providerId}|${it.releaseId}" }
        val selectedOnlineLibrary = backup.onlineLibrary.mapNotNull { incoming ->
            val key = "${incoming.providerId}|${incoming.releaseId}"
            val current = currentOnlineLibrary[key]
            val currentTimestamp = current?.let { maxOf(it.lastWatchedAt, it.lastOpenedAt, it.favoriteAddedAt) }
                ?: Long.MIN_VALUE
            val incomingTimestamp = maxOf(incoming.lastWatchedAt, incoming.lastOpenedAt, incoming.favoriteAddedAt)
            selectByTimestamp(current, incoming, currentTimestamp, incomingTimestamp, mergePolicy)
                .takeUnless { it === current }
        }
        val currentOnlineProgress = onlineRepository.snapshotOnlineProgress()
        val selectedOnlineProgress = backup.onlineProgress.mapNotNull { (key, incoming) ->
            val current = currentOnlineProgress[key]
            val selected = selectByTimestamp(
                current = current,
                incoming = incoming,
                currentTimestamp = current?.lastWatchedAt ?: Long.MIN_VALUE,
                incomingTimestamp = incoming.lastWatchedAt,
                policy = mergePolicy,
            )
            selected.takeUnless { it === current }?.let { key to it }
        }.toMap()
        onlineRepository.restoreOnlineState(selectedOnlineLibrary, selectedOnlineProgress)
        BackupRestoreResult(
            progressRestored = progressRestored,
            metadataRestored = metadataRestored,
            linksRestored = linksRestored,
            groupingOverridesRestored = overridesRestored,
            onlineLibraryRestored = selectedOnlineLibrary.size,
            onlineProgressRestored = selectedOnlineProgress.size,
            skipped = skipped,
        )
    }
}

internal const val BACKUP_FORMAT_VERSION = 2
