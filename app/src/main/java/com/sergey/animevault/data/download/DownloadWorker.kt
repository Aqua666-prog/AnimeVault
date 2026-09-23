package com.sergey.animevault.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.R
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.ProviderStreamRanker
import com.sergey.animevault.data.online.UnifiedReleaseReference
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.roundToInt

/**
 * Generation-aware, multi-source offline download worker.
 *
 * 2.0 resolves fresh candidates for every attempt, probes the transport before downloading,
 * falls through to another CDN/provider when possible, refreshes expired URLs on the next round,
 * persists speed/ETA and verifies the resulting media before importing it.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val application: AnimeVaultApplication
        get() = applicationContext as AnimeVaultApplication
    private val store: DownloadStore
        get() = application.container.downloadStore
    private val importer: DownloadedMediaImporter
        get() = application.container.downloadedMediaImporter
    private val onlineRepository
        get() = application.container.onlineRepository
    private val routeHealthTracker
        get() = application.container.downloadRouteHealthTracker

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val action = inputData.getString(KEY_ACTION) ?: ACTION_DOWNLOAD
        val operationToken = inputData.getString(KEY_OPERATION_TOKEN) ?: return Result.success()
        val entry = store.get(id) ?: return Result.failure()
        if (!entry.belongsToOperation(operationToken)) return Result.success()
        ensureNotificationChannel()

        return when (action) {
            ACTION_REMOVE -> remove(id, operationToken, entry)
            ACTION_DOWNLOAD -> orchestrateDownload(id, operationToken)
            else -> Result.failure()
        }
    }

    private suspend fun orchestrateDownload(
        id: String,
        operationToken: String,
    ): Result {
        var lastFailure: DownloadFailure? = null
        var lastError: Throwable? = null
        var needsFreshRound = false

        for (round in 0 until MAX_RESOLUTION_ROUNDS) {
            val latest = store.get(id) ?: return Result.success()
            if (!latest.belongsToOperation(operationToken) || !latest.status.canDownload) return Result.success()

            markResolving(id, operationToken, round)
            val stored = store.mediaSource(id)?.let { normalizeSource(it, latest) }
            val fresh = resolveMediaSources(latest)
            val candidates = buildCandidateList(fresh, stored)
            if (candidates.isEmpty()) {
                return failDownload(
                    id,
                    operationToken,
                    DownloadFailureKind.UNSUPPORTED,
                    applicationContext.getString(R.string.download_unavailable_link),
                )
            }

            candidates.forEachIndexed { index, candidate ->
                val current = store.get(id) ?: return Result.success()
                if (!current.belongsToOperation(operationToken) || !current.status.canDownload) return Result.success()
                val providerId = candidate.providerId
                if (!routeHealthTracker.shouldAttempt(candidate)) {
                    Log.d(TAG, "Skipping download route in cooldown provider=$providerId host=${candidate.host}")
                    return@forEachIndexed
                }
                if (providerId != null && !onlineRepository.shouldAttemptDownload(providerId)) {
                    Log.d(TAG, "Skipping download provider in cooldown provider=$providerId host=${candidate.host}")
                    return@forEachIndexed
                }

                val stage = buildCandidateStage(candidate, index + 1, candidates.size, "Проверка")
                updateCandidateState(id, operationToken, candidate, stage)
                val engine = NativeDownloadEngine()
                val probeStarted = monotonicNowMs()
                try {
                    engine.probe(
                        source = candidate,
                        preferredQuality = current.quality,
                        forceHls = candidate.streamType == OnlineStreamType.HLS,
                    )
                    val probeLatency = monotonicNowMs() - probeStarted
                    routeHealthTracker.recordSuccess(candidate, probeLatency)
                    providerId?.let {
                        onlineRepository.recordDownloadProbeSuccess(
                            it,
                            probeLatency,
                            "CDN отвечает${candidate.host?.let { host -> ": $host" }.orEmpty()}",
                        )
                    }
                } catch (error: CancellationException) {
                    markPaused(id, operationToken)
                    throw error
                } catch (error: Throwable) {
                    val failure = DownloadFailureClassifier.classify(error)
                    lastFailure = failure
                    lastError = error
                    needsFreshRound = needsFreshRound || (failure.shouldRefreshSource && candidate.refreshable)
                    val probeLatency = monotonicNowMs() - probeStarted
                    routeHealthTracker.recordFailure(candidate, failure, probeLatency)
                    providerId?.let {
                        onlineRepository.recordDownloadProbeFailure(it, probeLatency, error)
                    }
                    recordCandidateFailure(id, operationToken, failure, candidate, "Проверка потока")
                    Log.w(TAG, "Download preflight failed id=$id source=${candidate.safeDescription()}", error)
                    return@forEachIndexed
                }

                if (!store.updateMediaSource(id, operationToken, candidate)) return Result.success()
                val result = downloadCandidate(id, operationToken, candidate)
                when (result) {
                    CandidateResult.Success -> return Result.success()
                    is CandidateResult.Failure -> {
                        lastFailure = result.failure
                        lastError = result.error
                        needsFreshRound = needsFreshRound || (result.failure.shouldRefreshSource && candidate.refreshable)
                    }
                    CandidateResult.Stopped -> return Result.success()
                }
            }

            // A 401/403/404/410 may mean a signed URL expired while it was in the queue.
            // Resolve once more instead of hammering the same stale candidate through WorkManager.
            if (!needsFreshRound || round == MAX_RESOLUTION_ROUNDS - 1) break
            needsFreshRound = false
        }

        val failure = lastFailure ?: DownloadFailure(
            DownloadFailureKind.UNKNOWN,
            retryable = true,
            shouldRefreshSource = false,
            message = applicationContext.getString(R.string.download_failed),
        )
        return if (failure.retryable && runAttemptCount < MAX_WORK_RETRIES) {
            queueRetry(id, operationToken, lastError ?: IllegalStateException(failure.message), failure)
        } else {
            failDownload(id, operationToken, failure.kind, failure.message)
        }
    }

    private suspend fun downloadCandidate(
        id: String,
        operationToken: String,
        source: DownloadMediaSource,
    ): CandidateResult {
        val entry = store.get(id) ?: return CandidateResult.Stopped
        val started = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.DOWNLOADING,
                attemptCount = current.attemptCount + 1,
                failureKind = null,
                diagnosticStage = buildCandidateStage(source, current.attemptCount + 1, null, "Загрузка"),
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (started?.belongsToOperation(operationToken) != true || started.status != DownloadStatus.DOWNLOADING) {
            return CandidateResult.Stopped
        }

        val providerId = source.providerId
        val candidateStarted = monotonicNowMs()
        return try {
            setForeground(createForegroundInfo(started, started.progressPercent))
            val targetDirectory = File(applicationContext.filesDir, "downloads")
            var lastPersistAt = 0L
            var lastNotificationAt = 0L
            val estimator = DownloadProgressEstimator()
            val result = NativeDownloadEngine().download(
                source = source,
                targetDirectory = targetDirectory,
                fileStem = id,
                preferredQuality = started.quality,
                forceHls = source.streamType == OnlineStreamType.HLS,
            ) progress@{ nativeProgress ->
                val latest = store.get(id)
                if (latest?.belongsToOperation(operationToken) != true || latest.status != DownloadStatus.DOWNLOADING) {
                    return@progress
                }
                val now = System.currentTimeMillis()
                val estimate = estimator.sample(
                    bytesDownloaded = nativeProgress.bytesDownloaded,
                    contentLength = nativeProgress.contentLength,
                    completedItems = nativeProgress.completedItems,
                    totalItems = nativeProgress.totalItems,
                    nowMs = now,
                )
                if (now - lastPersistAt >= PROGRESS_PERSIST_INTERVAL_MS ||
                    nativeProgress.completedItems != latest.completedItems
                ) {
                    lastPersistAt = now
                    store.update(id) { current ->
                        if (!current.belongsToOperation(operationToken) || current.status != DownloadStatus.DOWNLOADING) {
                            current
                        } else {
                            current.copy(
                                progressPercent = nativeProgress.percent,
                                bytesDownloaded = nativeProgress.bytesDownloaded,
                                contentLength = nativeProgress.contentLength,
                                completedItems = nativeProgress.completedItems,
                                totalItems = nativeProgress.totalItems,
                                speedBytesPerSecond = estimate.speedBytesPerSecond,
                                etaSeconds = estimate.etaSeconds,
                                diagnosticStage = "Загрузка ${nativeProgress.completedItems}/${nativeProgress.totalItems}",
                                updatedAt = now,
                            )
                        }
                    }
                }
                if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                    lastNotificationAt = now
                    notificationManager().notify(
                        notificationId(id),
                        buildNotification(latest.copy(
                            speedBytesPerSecond = estimate.speedBytesPerSecond,
                            etaSeconds = estimate.etaSeconds,
                        ), nativeProgress.percent, false),
                    )
                }
            }

            val beforeVerify = store.get(id)
            if (beforeVerify?.belongsToOperation(operationToken) != true ||
                beforeVerify.status != DownloadStatus.DOWNLOADING
            ) return CandidateResult.Stopped
            store.update(id) { current ->
                if (!current.belongsToOperation(operationToken)) current else current.copy(
                    status = DownloadStatus.VERIFYING,
                    speedBytesPerSecond = 0L,
                    etaSeconds = null,
                    diagnosticStage = "Проверка файла",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            try {
                DownloadedMediaVerifier.verify(result.file)
            } catch (verifyError: Throwable) {
                result.file.delete()
                throw DownloadVerificationException(verifyError.message ?: "Проверка медиафайла не пройдена", verifyError)
            }

            val beforeImport = store.get(id)
            if (beforeImport?.belongsToOperation(operationToken) != true ||
                beforeImport.status != DownloadStatus.VERIFYING
            ) return CandidateResult.Stopped
            updateStage(id, operationToken, "Добавление в медиатеку")
            val previousFile = beforeImport.localFilePath?.let(::File)
            val localEpisodeId = importer.import(beforeImport, result)
            val completed = store.update(id) { current ->
                if (!current.belongsToOperation(operationToken) || current.status != DownloadStatus.VERIFYING) {
                    current
                } else {
                    current.copy(
                        status = DownloadStatus.COMPLETED,
                        quality = result.selectedQuality ?: source.quality ?: current.quality,
                        streamType = source.streamType ?: current.streamType,
                        streamProviderId = source.providerId ?: current.streamProviderId,
                        streamProviderName = source.providerName ?: current.streamProviderName,
                        sourceHost = source.host ?: current.sourceHost,
                        progressPercent = 100f,
                        bytesDownloaded = result.file.length(),
                        contentLength = result.file.length(),
                        localFilePath = result.file.absolutePath,
                        localMimeType = result.mimeType,
                        localEpisodeId = localEpisodeId,
                        completedItems = result.totalItems,
                        totalItems = result.totalItems,
                        speedBytesPerSecond = 0L,
                        etaSeconds = 0L,
                        failureKind = null,
                        diagnosticStage = "Доступно офлайн",
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            if (completed?.belongsToOperation(operationToken) == true && completed.status == DownloadStatus.COMPLETED) {
                if (previousFile != null && previousFile.absolutePath != result.file.absolutePath) previousFile.delete()
                notificationManager().notify(notificationId(id), buildNotification(completed, 100f, true))
            }
            val downloadLatency = monotonicNowMs() - candidateStarted
            routeHealthTracker.recordSuccess(source, downloadLatency)
            providerId?.let {
                onlineRepository.recordDownloadSuccess(
                    it,
                    downloadLatency,
                    "Скачивание завершено${source.host?.let { host -> ": $host" }.orEmpty()}",
                )
            }
            CandidateResult.Success
        } catch (error: CancellationException) {
            markPaused(id, operationToken)
            throw error
        } catch (error: Throwable) {
            if (isStopped) {
                markPaused(id, operationToken)
                CandidateResult.Stopped
            } else {
                val failure = if (error is DownloadVerificationException) {
                    DownloadFailure(
                        DownloadFailureKind.VERIFICATION,
                        retryable = true,
                        shouldRefreshSource = false,
                        message = error.message ?: "Проверка файла не пройдена",
                    )
                } else {
                    DownloadFailureClassifier.classify(error)
                }
                val downloadLatency = monotonicNowMs() - candidateStarted
                routeHealthTracker.recordFailure(source, failure, downloadLatency)
                providerId?.let {
                    onlineRepository.recordDownloadFailure(it, downloadLatency, error)
                }
                recordCandidateFailure(id, operationToken, failure, source, "Ошибка загрузки")
                Log.e(TAG, "Native download failed id=$id source=${source.safeDescription()}", error)
                CandidateResult.Failure(failure, error)
            }
        }
    }

    private suspend fun resolveMediaSources(entry: DownloadEntry): List<DownloadMediaSource> {
        return try {
            val release = onlineRepository.getRelease(entry.providerId, entry.releaseId)
            val episode = release.episodes.firstOrNull { it.id == entry.episodeId }
                ?: release.episodes.minByOrNull { candidate ->
                    val left = candidate.ordinal ?: Double.MAX_VALUE
                    val right = entry.episodeOrdinal ?: Double.MAX_VALUE
                    kotlin.math.abs(left - right)
                }
                ?: return emptyList()
            val directStreams = onlineRepository.resolveStreams(entry.providerId, entry.releaseId, episode)
                .filter(OnlineStream::isDownloadable)
            val fallbackStreams = if (entry.providerId == OnlineProviderIds.UNIFIED) {
                emptyList()
            } else {
                resolveUnifiedFallbackStreams(entry)
            }
            rankCandidates(entry, (directStreams + fallbackStreams).distinctBy { stream ->
                listOf(stream.providerId.orEmpty(), stream.url, stream.quality?.toString().orEmpty(), stream.translationPreferenceKey.orEmpty())
                    .joinToString("\u001F")
            }).map { stream ->
                stream.toDownloadMediaSource().let { normalizeSource(it, entry) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Source refresh failed id=${entry.id}; stored URL may still work", error)
            emptyList()
        }
    }

    private suspend fun resolveUnifiedFallbackStreams(entry: DownloadEntry): List<OnlineStream> {
        return try {
            val catalog = onlineRepository.getCatalog(
                providerId = OnlineProviderIds.UNIFIED,
                page = 1,
                limit = 12,
                search = entry.releaseName,
            )
            val unifiedCard = catalog.releases.firstOrNull { card ->
                runCatching { UnifiedReleaseReference.decode(card.id) }.getOrNull()?.members?.any { member ->
                    member.providerId == entry.providerId && member.releaseId == entry.releaseId
                } == true
            } ?: return emptyList()
            val release = onlineRepository.getRelease(OnlineProviderIds.UNIFIED, unifiedCard.id)
            val episode = release.episodes.firstOrNull { candidate ->
                candidate.sources.any { source ->
                    source.providerId == entry.providerId &&
                        source.releaseId == entry.releaseId &&
                        source.episodeId == entry.episodeId
                }
            } ?: release.episodes.minByOrNull { candidate ->
                val left = candidate.ordinal ?: Double.MAX_VALUE
                val right = entry.episodeOrdinal ?: Double.MAX_VALUE
                kotlin.math.abs(left - right)
            } ?: return emptyList()
            onlineRepository.resolveStreams(OnlineProviderIds.UNIFIED, release.id, episode)
                .filter(OnlineStream::isDownloadable)
                .filter { it.url.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.d(TAG, "Unified fallback discovery failed id=${entry.id}: ${error.message}")
            emptyList()
        }
    }

    private fun rankCandidates(entry: DownloadEntry, streams: List<OnlineStream>): List<OnlineStream> {
        val health = onlineRepository.healthStates.value
        val preferredProvider = entry.streamProviderId
        val qualityCandidates = if (!onlineRepository.downloadQualityFallback.value && entry.quality != null) {
            // Unknown quality may still be a master HLS playlist containing the requested rendition.
            streams.filter { it.quality == null || it.quality == entry.quality }
        } else {
            streams
        }
        return qualityCandidates.withIndex().sortedWith(
            compareByDescending<IndexedValue<OnlineStream>> { indexed ->
                val stream = indexed.value
                val providerId = stream.providerId ?: entry.providerId.takeUnless { it == OnlineProviderIds.UNIFIED }
                var score = ProviderStreamRanker.downloadScore(
                    stream = stream,
                    health = providerId?.let(health::get),
                    providerPriority = providerId?.let(onlineRepository::providerPriority) ?: 0,
                )
                if (!entry.translationKey.isNullOrBlank() && stream.translationPreferenceKey == entry.translationKey) score += 10_000
                if (entry.quality != null && stream.quality == entry.quality) score += 5_000
                if (!preferredProvider.isNullOrBlank() && providerId == preferredProvider) score += 1_500
                if (!entry.sourceName.isNullOrBlank() && stream.sourceName == entry.sourceName) score += 500
                // Never prefer an upscaled/higher bandwidth fallback over the requested quality.
                if (entry.quality != null && stream.quality != null && stream.quality > entry.quality) score -= 1_000
                score
            }.thenBy { it.index },
        ).map(IndexedValue<OnlineStream>::value)
    }

    private fun buildCandidateList(
        fresh: List<DownloadMediaSource>,
        stored: DownloadMediaSource?,
    ): List<DownloadMediaSource> {
        val all = buildList {
            fresh.forEach { source -> addAll(source.withKnownCdnAlternatives()) }
            if (stored != null) addAll(stored.withKnownCdnAlternatives())
        }
        return all.distinctBy { candidate ->
            listOf(
                candidate.providerId.orEmpty(),
                candidate.url,
                candidate.quality?.toString().orEmpty(),
                candidate.translationKey.orEmpty(),
            ).joinToString("\u001F")
        }
    }

    private fun DownloadMediaSource.withKnownCdnAlternatives(): List<DownloadMediaSource> {
        if (providerId != OnlineProviderIds.ANI_LIBERTY && host !in setOf("cache.libria.fun", "cache-rfn.libria.fun")) {
            return listOf(this)
        }
        val alternate = when (host) {
            "cache-rfn.libria.fun" -> url.replace("://cache-rfn.libria.fun", "://cache.libria.fun")
            "cache.libria.fun" -> url.replace("://cache.libria.fun", "://cache-rfn.libria.fun")
            else -> null
        }
        return if (alternate.isNullOrBlank() || alternate == url) listOf(this) else listOf(this, copy(url = alternate))
    }

    private fun normalizeSource(source: DownloadMediaSource, entry: DownloadEntry): DownloadMediaSource {
        val fallbackProviderId = entry.providerId.takeUnless { it == OnlineProviderIds.UNIFIED }
        return source.copy(
            providerId = source.providerId ?: entry.streamProviderId ?: fallbackProviderId,
            providerName = source.providerName ?: entry.streamProviderName ?: entry.providerName.takeIf { fallbackProviderId != null },
            streamType = source.streamType ?: entry.streamType,
            quality = source.quality ?: entry.quality,
            translation = source.translation ?: entry.translation,
            translationKey = source.translationKey ?: entry.translationKey,
            sourceName = source.sourceName ?: entry.sourceName,
        )
    }

    private suspend fun updateCandidateState(
        id: String,
        operationToken: String,
        source: DownloadMediaSource,
        stage: String,
    ) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken)) current else current.copy(
                status = DownloadStatus.RESOLVING,
                streamProviderId = source.providerId ?: current.streamProviderId,
                streamProviderName = source.providerName ?: current.streamProviderName,
                sourceHost = source.host ?: current.sourceHost,
                streamType = source.streamType ?: current.streamType,
                sourceName = source.sourceName ?: current.sourceName,
                diagnosticStage = stage,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun recordCandidateFailure(
        id: String,
        operationToken: String,
        failure: DownloadFailure,
        source: DownloadMediaSource,
        stage: String,
    ) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.RESOLVING,
                streamProviderId = source.providerId ?: current.streamProviderId,
                streamProviderName = source.providerName ?: current.streamProviderName,
                sourceHost = source.host ?: current.sourceHost,
                failureKind = failure.kind,
                diagnosticStage = "$stage · ${source.providerName ?: source.providerId ?: source.host ?: "источник"}",
                errorMessage = failure.message,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun markResolving(id: String, operationToken: String, round: Int) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.RESOLVING,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                diagnosticStage = if (round == 0) "Поиск рабочего потока" else "Обновление устаревшей ссылки",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun remove(id: String, operationToken: String, entry: DownloadEntry): Result {
        return try {
            store.update(id) { current ->
                if (!current.belongsToOperation(operationToken)) current else current.copy(
                    status = DownloadStatus.REMOVING,
                    diagnosticStage = "Удаление из медиатеки",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            importer.remove(entry)
            val directory = File(applicationContext.filesDir, "downloads")
            directory.listFiles()
                ?.filter { it.name.startsWith("$id.") || it.name == ".$id-parts" }
                ?.forEach(File::deleteRecursively)
            if (store.get(id)?.belongsToOperation(operationToken) == true) store.remove(id)
            notificationManager().cancel(notificationId(id))
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(id, operationToken, error.message ?: applicationContext.getString(R.string.download_remove_failed))
        }
    }

    private suspend fun updateStage(id: String, operationToken: String, stage: String) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken)) current else current.copy(
                diagnosticStage = stage,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun markPaused(id: String, operationToken: String) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.PAUSED,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                diagnosticStage = "Пауза — прогресс сохранён",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun queueRetry(
        id: String,
        operationToken: String,
        error: Throwable,
        failure: DownloadFailure = DownloadFailureClassifier.classify(error),
    ): Result {
        val queued = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.RETRY_WAIT,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                failureKind = failure.kind,
                diagnosticStage = "Повторная попытка ${runAttemptCount + 1}/${MAX_WORK_RETRIES + 1}",
                errorMessage = failure.message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return if (queued?.belongsToOperation(operationToken) == true && queued.status == DownloadStatus.RETRY_WAIT) {
            // WorkManager will run the same generation again; RETRY_WAIT is accepted by canDownload.
            Result.retry()
        } else {
            Result.success()
        }
    }

    private suspend fun failDownload(
        id: String,
        operationToken: String,
        kind: DownloadFailureKind,
        message: String,
    ): Result {
        val failed = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.FAILED,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                failureKind = kind,
                diagnosticStage = "Ошибка загрузки",
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return if (failed?.belongsToOperation(operationToken) == true && failed.status == DownloadStatus.FAILED) {
            Result.failure()
        } else {
            Result.success()
        }
    }

    private suspend fun fail(id: String, operationToken: String, message: String): Result {
        val failed = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken)) current else current.copy(
                status = DownloadStatus.FAILED,
                failureKind = DownloadFailureKind.UNKNOWN,
                diagnosticStage = "Ошибка операции",
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return if (failed?.belongsToOperation(operationToken) == true && failed.status == DownloadStatus.FAILED) {
            Result.failure()
        } else {
            Result.success()
        }
    }

    private val DownloadStatus.canDownload: Boolean
        get() = this in setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.RETRY_WAIT,
            DownloadStatus.VERIFYING,
        )

    private fun createForegroundInfo(entry: DownloadEntry, progress: Float): ForegroundInfo {
        val id = notificationId(entry.id)
        val notification = buildNotification(entry, progress, false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun buildNotification(entry: DownloadEntry, progress: Float, complete: Boolean) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (complete) applicationContext.getString(R.string.download_episode_complete) else entry.releaseName)
            .setContentText(
                if (complete) entry.episodeLabel
                else buildString {
                    append(entry.episodeLabel)
                    append(" · ${progress.roundToInt()}%")
                    if (entry.speedBytesPerSecond > 0L) append(" · ${formatCompactSpeed(entry.speedBytesPerSecond)}")
                    entry.etaSeconds?.takeIf { it > 0L }?.let { append(" · ${formatEta(it)}") }
                },
            )
            .setOnlyAlertOnce(true)
            .setOngoing(!complete)
            .setAutoCancel(complete)
            .setProgress(100, progress.roundToInt().coerceIn(0, 100), false)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationManager(): NotificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notificationId(id: String): Int = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)

    private fun DownloadMediaSource.safeDescription(): String = runCatching {
        val uri = url.toUri()
        "${providerId.orEmpty()} ${uri.scheme ?: "unknown"}://${uri.host ?: "unknown"}${uri.path.orEmpty()}"
    }.getOrDefault("invalid-url")

    private fun buildCandidateStage(source: DownloadMediaSource, index: Int, total: Int?, action: String): String {
        val sourceLabel = source.providerName ?: source.providerId ?: source.host ?: "источник"
        val quality = source.quality?.let { " · ${it}p" }.orEmpty()
        val ordinal = total?.let { " $index/$it" }.orEmpty()
        return "$action$ordinal · $sourceLabel$quality"
    }

    private fun formatCompactSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f КБ/с", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond Б/с"
    }

    private fun formatEta(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private sealed interface CandidateResult {
        data object Success : CandidateResult
        data object Stopped : CandidateResult
        data class Failure(val failure: DownloadFailure, val error: Throwable) : CandidateResult
    }

    private class DownloadVerificationException(message: String, cause: Throwable) : Exception(message, cause)

    companion object {
        private const val TAG = "AnimeVaultDownload"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ACTION = "download_action"
        const val KEY_OPERATION_TOKEN = "download_operation_token"
        const val ACTION_DOWNLOAD = "download"
        const val ACTION_REMOVE = "remove"
        private const val CHANNEL_ID = "animevault_downloads"
        private const val MAX_WORK_RETRIES = 3
        private const val MAX_RESOLUTION_ROUNDS = 2
        private const val PROGRESS_PERSIST_INTERVAL_MS = 500L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
