package com.sergey.animevault.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineStream
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadRepository(
    context: Context,
    private val store: DownloadStore,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    val entries: StateFlow<List<DownloadEntry>> = store.entries

    suspend fun initialize() {
        store.initialize()
        // Migrate queued JSON-era jobs to generation-aware WorkManager requests.
        store.getAll()
            .filter { it.isActive && it.operationToken == null }
            .forEach { legacy ->
                val token = newOperationToken()
                store.update(legacy.id) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        operationToken = token,
                        diagnosticStage = "Восстановление очереди",
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                enqueueWork(legacy.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
            }
    }

    suspend fun enqueue(
        release: OnlineReleaseDetails,
        episode: OnlineEpisode,
        stream: OnlineStream,
    ): DownloadEntry {
        require(stream.isDownloadable()) { "Этот тип потока нельзя скачать" }
        val existing = store.findLogical(release.providerId, release.id, episode.id)
        val id = existing?.id ?: downloadId(release.providerId, release.id, episode.id, stream)
        val token = newOperationToken()
        val entry = (existing ?: DownloadEntry(
            id = id,
            providerId = release.providerId,
            providerName = release.providerName,
            releaseId = release.id,
            releaseName = release.name,
            episodeId = episode.id,
            episodeOrdinal = episode.ordinal,
            episodeName = episode.name,
            quality = stream.quality,
            translation = stream.translation,
            translationKey = stream.translationPreferenceKey,
            sourceName = stream.sourceName,
            streamType = stream.type,
            streamProviderId = stream.providerId ?: release.providerId.takeUnless { it == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED },
            streamProviderName = stream.providerName ?: release.providerName.takeUnless { release.providerId == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED },
            sourceHost = stream.toDownloadMediaSource().host,
        )).copy(
            providerName = release.providerName,
            releaseName = release.name,
            episodeOrdinal = episode.ordinal,
            episodeName = episode.name,
            quality = stream.quality,
            translation = stream.translation,
            translationKey = stream.translationPreferenceKey,
            sourceName = stream.sourceName,
            streamType = stream.type,
            streamProviderId = stream.providerId ?: release.providerId.takeUnless { it == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED },
            streamProviderName = stream.providerName ?: release.providerName.takeUnless { release.providerId == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED },
            sourceHost = stream.toDownloadMediaSource().host,
            status = DownloadStatus.QUEUED,
            operationToken = token,
            progressPercent = 0f,
            bytesDownloaded = 0L,
            contentLength = -1L,
            completedItems = 0,
            totalItems = 0,
            speedBytesPerSecond = 0L,
            etaSeconds = null,
            attemptCount = 0,
            failureKind = null,
            diagnosticStage = "Поставлено в очередь",
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
        )
        store.put(entry, stream.toDownloadMediaSource())
        enqueueWork(entry.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
        return entry
    }

    suspend fun pause(id: String) {
        val invalidationToken = newOperationToken()
        store.update(id) { entry ->
            if (entry.status == DownloadStatus.REMOVING || entry.status == DownloadStatus.COMPLETED) {
                entry
            } else {
                entry.copy(
                    status = DownloadStatus.PAUSED,
                    operationToken = invalidationToken,
                    speedBytesPerSecond = 0L,
                    etaSeconds = null,
                    diagnosticStage = "Пауза",
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        workManager.cancelUniqueWork(workName(id))
    }

    suspend fun resume(id: String) {
        val entry = store.get(id) ?: return
        if (entry.status !in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.MISSING, DownloadStatus.RETRY_WAIT)) return
        val token = newOperationToken()
        store.update(id) {
            it.copy(
                status = DownloadStatus.QUEUED,
                operationToken = token,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                failureKind = null,
                diagnosticStage = "Возобновление",
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        enqueueWork(entry.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
    }

    suspend fun remove(id: String) {
        if (store.get(id) == null) return
        val token = newOperationToken()
        store.update(id) {
            it.copy(
                status = DownloadStatus.REMOVING,
                operationToken = token,
                diagnosticStage = "Удаление",
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        workManager.cancelUniqueWork(workName(id))
        enqueueWork(id, DownloadWorker.ACTION_REMOVE, token, ExistingWorkPolicy.REPLACE, requiresNetwork = false)
    }

    fun entry(id: String): DownloadEntry? = store.snapshot(id)

    fun playbackSource(id: String): Pair<DownloadEntry, DownloadMediaSource>? {
        val entry = store.snapshot(id)?.takeIf(DownloadEntry::isPlayableOffline) ?: return null
        val file = entry.localFilePath?.let(::File)?.takeIf { it.isFile && it.length() > 0L } ?: return null
        return entry to DownloadMediaSource(file.toURI().toString(), emptyMap())
    }

    private fun enqueueWork(
        id: String,
        action: String,
        operationToken: String,
        policy: ExistingWorkPolicy,
        requiresNetwork: Boolean = action == DownloadWorker.ACTION_DOWNLOAD,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_DOWNLOAD_ID, id)
                    .putString(DownloadWorker.KEY_ACTION, action)
                    .putString(DownloadWorker.KEY_OPERATION_TOKEN, operationToken)
                    .build(),
            )
            .addTag(TAG_DOWNLOADS)
            .addTag("download:$id")
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    private fun workName(id: String) = "animevault-download-$id"

    private fun newOperationToken(): String = UUID.randomUUID().toString()

    companion object {
        const val TAG_DOWNLOADS = "animevault-downloads"
    }
}
