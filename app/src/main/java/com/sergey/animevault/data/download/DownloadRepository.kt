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
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineStream
import kotlinx.coroutines.flow.StateFlow
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
    private val seasonMutex = Mutex()

    private fun rememberSeasonEntry(id: String, policy: SeasonQualityPolicy, wifiOnly: Boolean) {
        seasonPrefs.edit().putString(id, "${policy.name}:${if (wifiOnly) 1 else 0}").apply()
    }

    private fun seasonSettings(id: String): Pair<SeasonQualityPolicy, Boolean>? {
        val saved = seasonPrefs.getString(id, null)?.split(':') ?: return null
        if (saved.size != 2) return null
        val policy = runCatching { SeasonQualityPolicy.valueOf(saved[0]) }.getOrNull() ?: return null
        return policy to (saved[1] == "1")
    }

    private fun seasonWorkName(providerId: String, releaseId: String): String =
        "animevault-season-$providerId-$releaseId"

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
    ): Int = seasonMutex.withLock {
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
            store.put(entry, stream.toDownloadMediaSource())
            rememberSeasonEntry(entry.id, qualityPolicy, wifiOnly)
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
            chain.enqueue()
        }
        requests.size
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
        seasonPrefs.edit().remove(entry.id).apply()
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
        val batch = seasonSettings(entry.id)
        if (batch != null) {
            val (policy, wifiOnly) = batch
            workManager.beginUniqueWork(
                seasonWorkName(entry.providerId, entry.releaseId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                createWorkRequest(entry.id, DownloadWorker.ACTION_DOWNLOAD, token,
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED, policy),
            ).enqueue()
        } else {
            enqueueWork(entry.id, DownloadWorker.ACTION_DOWNLOAD, token, ExistingWorkPolicy.REPLACE)
        }
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
        seasonPrefs.edit().remove(id).apply()
        enqueueWork(id, DownloadWorker.ACTION_REMOVE, token, ExistingWorkPolicy.REPLACE, requiresNetwork = false)
    }

    fun entry(id: String): DownloadEntry? = store.snapshot(id)

    fun playbackSource(id: String): Pair<DownloadEntry, DownloadMediaSource>? {
        val entry = store.snapshot(id)?.takeIf(DownloadEntry::isPlayableOffline) ?: return null
        val location = entry.localFilePath?.takeIf { SharedDownloadStorage.exists(appContext, it) }
            ?: return null
        return entry to DownloadMediaSource(SharedDownloadStorage.playbackUrl(location), emptyMap())
    }

    private fun enqueueWork(
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
        workManager.enqueueUniqueWork(workName(id), policy, request)
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
            .addTag("download:$id")
            .build()
    }

    private fun workName(id: String) = "animevault-download-$id"

    private fun newOperationToken(): String = UUID.randomUUID().toString()

    companion object {
        const val TAG_DOWNLOADS = "animevault-downloads"
    }
}
