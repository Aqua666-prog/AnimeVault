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
import com.sergey.animevault.data.online.OnlineStreamType
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

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

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val action = inputData.getString(KEY_ACTION) ?: ACTION_DOWNLOAD
        val operationToken = inputData.getString(KEY_OPERATION_TOKEN) ?: return Result.success()
        val entry = store.get(id) ?: return Result.failure()
        if (!entry.belongsToOperation(operationToken)) return Result.success()
        ensureNotificationChannel()

        return when (action) {
            ACTION_REMOVE -> remove(id, operationToken, entry)
            ACTION_DOWNLOAD -> {
                val storedSource = store.mediaSource(id)
                    ?: return failDownload(id, operationToken, "Ссылка загрузки недоступна")
                updateStage(id, operationToken, "Обновление ссылки")
                val source = refreshMediaSource(entry) ?: storedSource
                Log.d(
                    TAG,
                    "Starting native download id=$id source=${source.safeDescription()} type=${entry.streamType} " +
                        "headers=${source.headers.keys}",
                )
                if (!store.updateMediaSource(id, operationToken, source)) return Result.success()
                val current = store.get(id) ?: return Result.success()
                if (!current.belongsToOperation(operationToken) || !current.status.canDownload) return Result.success()
                download(id, operationToken, current, source)
            }
            else -> Result.failure()
        }
    }

    private suspend fun download(
        id: String,
        operationToken: String,
        entry: DownloadEntry,
        source: DownloadMediaSource,
    ): Result {
        val started = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.DOWNLOADING,
                diagnosticStage = "Подготовка загрузки",
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (started?.belongsToOperation(operationToken) != true || started.status != DownloadStatus.DOWNLOADING) {
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo(entry, entry.progressPercent))
            val targetDirectory = File(applicationContext.filesDir, "downloads")
            var lastPersistAt = 0L
            var lastNotificationAt = 0L
            val result = NativeDownloadEngine().download(
                source = source,
                targetDirectory = targetDirectory,
                fileStem = id,
                preferredQuality = entry.quality,
                forceHls = entry.streamType == OnlineStreamType.HLS,
            ) progress@{ nativeProgress ->
                val latest = store.get(id)
                if (latest?.belongsToOperation(operationToken) != true || latest.status != DownloadStatus.DOWNLOADING) {
                    return@progress
                }
                val now = System.currentTimeMillis()
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
                        buildNotification(entry, nativeProgress.percent, false),
                    )
                }
            }

            val beforeImport = store.get(id)
            if (beforeImport?.belongsToOperation(operationToken) != true ||
                beforeImport.status != DownloadStatus.DOWNLOADING
            ) {
                return Result.success()
            }
            updateStage(id, operationToken, "Добавление в медиатеку")
            importer.import(beforeImport, result)
            val completed = store.update(id) { current ->
                if (!current.belongsToOperation(operationToken) || current.status != DownloadStatus.DOWNLOADING) {
                    current
                } else {
                    current.copy(
                        status = DownloadStatus.COMPLETED,
                        quality = result.selectedQuality ?: current.quality,
                        progressPercent = 100f,
                        bytesDownloaded = result.file.length(),
                        contentLength = result.file.length(),
                        localFilePath = result.file.absolutePath,
                        localMimeType = result.mimeType,
                        completedItems = result.totalItems,
                        totalItems = result.totalItems,
                        diagnosticStage = "Доступно офлайн",
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
            if (completed?.belongsToOperation(operationToken) == true && completed.status == DownloadStatus.COMPLETED) {
                notificationManager().notify(notificationId(id), buildNotification(entry, 100f, true))
            }
            Result.success()
        } catch (error: CancellationException) {
            markPaused(id, operationToken)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Native download failed id=$id source=${source.safeDescription()}", error)
            if (isStopped) {
                markPaused(id, operationToken)
                Result.success()
            } else if (error.isRetryableDownloadFailure() && runAttemptCount < MAX_WORK_RETRIES) {
                queueRetry(id, operationToken, error)
            } else {
                failDownload(id, operationToken, error.downloadFailureMessage())
            }
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
            fail(id, operationToken, error.message ?: "Не удалось удалить загрузку")
        }
    }

    private suspend fun refreshMediaSource(entry: DownloadEntry): DownloadMediaSource? {
        return try {
            val repository = application.container.onlineRepository
            val release = repository.getRelease(entry.providerId, entry.releaseId)
            val episode = release.episodes.firstOrNull { it.id == entry.episodeId } ?: return null
            val resolved = repository.resolveStreams(entry.providerId, entry.releaseId, episode)
                .filter { it.isDownloadable() && it.type == entry.streamType }
            val sameSource = entry.sourceName
                ?.takeIf(String::isNotBlank)
                ?.let { sourceName -> resolved.filter { it.sourceName == sourceName } }
                .orEmpty()
            val stream = chooseDownloadStream(
                streams = sameSource.ifEmpty { resolved },
                preferredTranslationKey = entry.translationKey,
                preferredQuality = entry.quality,
            ) ?: return null
            DownloadMediaSource(stream.url, stream.headers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Source refresh failed id=${entry.id}; using stored source", error)
            null
        }
    }

    private fun updateStage(id: String, operationToken: String, stage: String) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken)) current else current.copy(
                diagnosticStage = stage,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun markPaused(id: String, operationToken: String) {
        store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.PAUSED,
                diagnosticStage = "Пауза — прогресс сохранён",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun queueRetry(id: String, operationToken: String, error: Throwable): Result {
        val queued = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || current.status != DownloadStatus.DOWNLOADING) current else current.copy(
                status = DownloadStatus.QUEUED,
                diagnosticStage = "Ожидание повторной попытки ${runAttemptCount + 1}",
                errorMessage = error.downloadFailureMessage(),
                updatedAt = System.currentTimeMillis(),
            )
        }
        return if (queued?.belongsToOperation(operationToken) == true && queued.status == DownloadStatus.QUEUED) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun failDownload(id: String, operationToken: String, message: String): Result {
        val failed = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken) || !current.status.canDownload) current else current.copy(
                status = DownloadStatus.FAILED,
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

    private fun fail(id: String, operationToken: String, message: String): Result {
        val failed = store.update(id) { current ->
            if (!current.belongsToOperation(operationToken)) current else current.copy(
                status = DownloadStatus.FAILED,
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
        get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING

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
            .setContentTitle(if (complete) "Серия скачана" else entry.releaseName)
            .setContentText(if (complete) entry.episodeLabel else "${entry.episodeLabel} · ${progress.roundToInt()}%")
            .setOnlyAlertOnce(true)
            .setOngoing(!complete)
            .setAutoCancel(complete)
            .setProgress(100, progress.roundToInt().coerceIn(0, 100), false)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Загрузки AnimeVault", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationManager(): NotificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notificationId(id: String): Int = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)

    private fun DownloadMediaSource.safeDescription(): String = runCatching {
        val uri = url.toUri()
        "${uri.scheme ?: "unknown"}://${uri.host ?: "unknown"}${uri.path.orEmpty()}"
    }.getOrDefault("invalid-url")

    private fun Throwable.isRetryableDownloadFailure(): Boolean =
        this is IOException || cause?.isRetryableDownloadFailure() == true

    private fun Throwable.downloadFailureMessage(): String {
        val detail = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
        return "$detail: не удалось скачать серию"
    }

    companion object {
        private const val TAG = "AnimeVaultDownload"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ACTION = "download_action"
        const val KEY_OPERATION_TOKEN = "download_operation_token"
        const val ACTION_DOWNLOAD = "download"
        const val ACTION_REMOVE = "remove"
        private const val CHANNEL_ID = "animevault_downloads"
        private const val MAX_WORK_RETRIES = 3
        private const val PROGRESS_PERSIST_INTERVAL_MS = 500L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
