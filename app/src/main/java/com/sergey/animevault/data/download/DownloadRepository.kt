package com.sergey.animevault.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadRepository(
    context: Context,
    private val store: DownloadStore,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    val entries: StateFlow<List<DownloadEntry>> = store.entries
    private val seasonPrefs = appContext.getSharedPreferences("animevault_season_queue_v1", Context.MODE_PRIVATE)
    private val queueMutex = Mutex()

    private suspend fun rememberSeasonEntry(id: String, policy: SeasonQualityPolicy, wifiOnly: Boolean) {
        withContext(Dispatchers.IO) {
            check(seasonPrefs.edit().putString(id, "${policy.name}:${if (wifiOnly) 1 else 0}").commit()) {
                "Не удалось сохранить настройки очереди сезона"
            }
        }
    }

    private fun seasonSettings(id: String): Pair<SeasonQualityPolicy, Boolean>? {
        val saved = seasonPrefs.getString(id, null)?.split(':') ?: return null
        if (saved.size != 2) return null
        val policy = runCatching { SeasonQualityPolicy.valueOf(saved[0]) }.getOrNull() ?: return null
        return policy to (saved[1] == "1")
    }

    private fun seasonWorkName(providerId: String, releaseId: String): String =
        "animevault-season-$providerId-$releaseId"

    suspend fun initialize() = queueMutex.withLock {
        store.initialize()
        val recoverable = store.getAll().filter { it.isActive || it.status == DownloadStatus.REMOVING }
        for (entry in recoverable.sortedBy { it.createdAt }) {
            val token = entry.operationToken ?: newOperationToken()
            val activeWork = workManager.getWorkInfosByTagFlow("download:${entry.id}").first()
                .filterNot { it.state.isFinished }
            if (entry.operationToken != null && activeWork.any { info ->
                    operationTag(token) in info.tags || info.tags.none { it.startsWith(OPERATION_TAG_PREFIX) }
                }
            ) continue

            val recovered = store.update(entry.id) { current ->
                if (current.operationToken != entry.operationToken ||
                    (!current.isActive && current.status != DownloadStatus.REMOVING)
                ) current else current.copy(
                    status = if (current.status == DownloadStatus.REMOVING) current.status else DownloadStatus.QUEUED,
                    operationToken = token,
                    diagnosticStage = "Восстановление очереди",
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }?.takeIf { it.belongsToOperation(token) } ?: continue
            if (recovered.status == DownloadStatus.REMOVING) {
                enqueueWork(recovered.id, DownloadWorker.ACTION_REMOVE, token, ExistingWorkPolicy.REPLACE,
                    requiresNetwork = false)
            } else if (recovered.isActive) {
                scheduleDownload(recovered, token)
            }
        }
    }

    /**
     * Persist each episode before appending one-at-a-time WorkManager tasks.
     * Normal single-episode jobs keep their existing unique-work semantics.
     */
    suspend fun enqueueSeason(
        release: OnlineReleaseDetails,
        selections: List<Pair<OnlineEpisode, OnlineStream>>,
        preferredQuality: Int?,
        qualityPolicy: SeasonQualityPolicy,
        wifiOnly: Boolean,
    ): Int = queueMutex.withLock {
        val requests = mutableListOf<OneTimeWorkRequest>()
        val seen = mutableSetOf<String>()
        for ((episode, stream) in selections) {
            if (!seen.add(episode.id)) continue
            require(stream.isDownloadable()) { "Этот тип потока нельзя скачать" }
            val existing = store.findLogical(release.providerId, release.id, episode.id)
            if (existing?.status == DownloadStatus.COMPLETED ||
                existing?.status == DownloadStatus.REMOVING || existing?.isActive == true) continue
            val id = existing?.id ?: downloadId(release.providerId, release.id, episode.id, stream)
            val token = newOperationToken()
            val desiredQuality = preferredQuality ?: stream.quality
            val entry = (existing ?: DownloadEntry(
                id = id,
                providerId = release.providerId,
                providerName = release.providerName,
                releaseId = release.id,
                releaseName = release.name,
                episodeId = episode.id,
                episodeOrdinal = episode.ordinal,
                episodeName = episode.name,
                quality = desiredQuality,
                translation = stream.translation,
                translationKey = stream.translationPreferenceKey,
                sourceName = stream.sourceName,
                streamType = stream.type,
            )).copy(
                providerName = release.providerName,
                releaseName = release.name,
                episodeOrdinal = episode.ordinal,
                episodeName = episode.name,
                quality = desiredQuality,
                translation = stream.translation,
                translationKey = stream.translationPreferenceKey,
                sourceName = stream.sourceName,
                streamType = stream.type,
                streamProviderId = stream.providerId ?: release.providerId.takeUnless {
                    it == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED
                },
                streamProviderName = stream.providerName ?: release.providerName.takeUnless {
                    release.providerId == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED
                },
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
                diagnosticStage = "В очереди сезона",
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
            rememberSeasonEntry(entry.id, qualityPolicy, wifiOnly)
            store.put(entry, stream.toDownloadMediaSource())
            requests += createWorkRequest(
                entry.id, DownloadWorker.ACTION_DOWNLOAD, token,
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                qualityPolicy,
            )
        }
        if (requests.isNotEmpty()) {
            val chainName = seasonWorkName(release.providerId, release.id)
            var chain = workManager.beginUniqueWork(
                chainName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requests.first(),
            )
            requests.drop(1).forEach { chain = chain.then(it) }
            withContext(NonCancellable) { chain.enqueue().await() }
        }
        requests.size
    }

    suspend fun enqueue(
        release: OnlineReleaseDetails,
        episode: OnlineEpisode,
        stream: OnlineStream,
    ): DownloadEntry = queueMutex.withLock {
        require(stream.isDownloadable()) { "Этот тип потока нельзя скачать" }
        val existing = store.findLogical(release.providerId, release.id, episode.id)
        if (existing?.status == DownloadStatus.REMOVING) return@withLock existing
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
        seasonPrefs.edit().remove(entry.id).apply()
        withContext(NonCancellable) {
            enqueueWork(entry.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
        }
        entry
    }

    suspend fun pause(id: String): Unit = queueMutex.withLock {
        val invalidationToken = newOperationToken()
        val paused = store.update(id) { entry ->
            if (!entry.isActive) {
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
        if (paused?.belongsToOperation(invalidationToken) == true) {
            workManager.cancelUniqueWork(workName(id)).await()
        }
    }

    suspend fun resume(id: String): Unit = queueMutex.withLock {
        val entry = store.get(id) ?: return@withLock
        if (entry.status !in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.MISSING, DownloadStatus.RETRY_WAIT)) return@withLock
        val token = newOperationToken()
        val resumed = store.update(id) {
            if (it.operationToken != entry.operationToken || it.status != entry.status) it
            else it.copy(
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
        if (resumed?.belongsToOperation(token) == true) {
            withContext(NonCancellable) { scheduleDownload(resumed, token) }
        }
    }

    private suspend fun scheduleDownload(entry: DownloadEntry, token: String) {
        val batch = seasonSettings(entry.id)
        if (batch != null) {
            val (policy, wifiOnly) = batch
            workManager.beginUniqueWork(
                seasonWorkName(entry.providerId, entry.releaseId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                createWorkRequest(entry.id, DownloadWorker.ACTION_DOWNLOAD, token,
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED, policy),
            ).enqueue().await()
        } else {
            enqueueWork(entry.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun remove(id: String): Unit = queueMutex.withLock {
        if (store.get(id) == null) return@withLock
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
        withContext(NonCancellable) {
            seasonPrefs.edit().remove(id).apply()
            enqueueWork(id, DownloadWorker.ACTION_REMOVE, token, ExistingWorkPolicy.REPLACE, requiresNetwork = false)
        }
    }

    fun entry(id: String): DownloadEntry? = store.snapshot(id)

    fun playbackSource(id: String): Pair<DownloadEntry, DownloadMediaSource>? {
        val entry = store.snapshot(id)?.takeIf(DownloadEntry::isPlayableOffline) ?: return null
        val location = entry.localFilePath?.takeIf { SharedDownloadStorage.exists(appContext, it) }
            ?: return null
        return entry to DownloadMediaSource(SharedDownloadStorage.playbackUrl(location), emptyMap())
    }

    private suspend fun enqueueWork(
        id: String,
        action: String,
        operationToken: String,
        policy: ExistingWorkPolicy,
        requiresNetwork: Boolean = action == DownloadWorker.ACTION_DOWNLOAD,
    ) {
        val request = createWorkRequest(
            id, action, operationToken,
            if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
            null,
        )
        workManager.enqueueUniqueWork(workName(id), policy, request).await()
    }

    private fun createWorkRequest(
        id: String,
        action: String,
        operationToken: String,
        requiredNetwork: NetworkType,
        seasonQualityPolicy: SeasonQualityPolicy?,
    ): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(requiredNetwork)
            .build()
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_DOWNLOAD_ID, id)
                    .putString(DownloadWorker.KEY_ACTION, action)
                    .putString(DownloadWorker.KEY_OPERATION_TOKEN, operationToken)
                    .putBoolean(DownloadWorker.KEY_SEASON_CHAIN, seasonQualityPolicy != null)
                    .putString(DownloadWorker.KEY_SEASON_QUALITY_POLICY, seasonQualityPolicy?.name.orEmpty())
                    .build(),
            )
            .addTag(TAG_DOWNLOADS)
            .addTag(operationTag(operationToken))
            .addTag("download:$id")
            .build()
    }

    private fun workName(id: String) = "animevault-download-$id"

    private fun newOperationToken(): String = UUID.randomUUID().toString()

    private fun operationTag(token: String) = OPERATION_TAG_PREFIX + token

    companion object {
        private const val OPERATION_TAG_PREFIX = "download-operation:"
        const val TAG_DOWNLOADS = "animevault-downloads"
    }
}
