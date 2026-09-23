package com.sergey.animevault.data.download

import android.content.Context
import androidx.core.content.edit
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Room-backed download state with one-time migration from the old JSON store. */
class DownloadStore(
    context: Context,
    private val dao: DownloadDao,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureStore = SecureSessionStore(context)
    private val mutationMutex = Mutex()
    val entries: StateFlow<List<DownloadEntry>> = dao.observeAll()
        .map { rows -> rows.map(DownloadEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun initialize() = mutationMutex.withLock {
        if (!preferences.getBoolean(KEY_ROOM_MIGRATED, false)) {
            val roomIds = dao.getAll().mapTo(mutableSetOf(), DownloadEntity::id)
            val legacy = readLegacyEntries().filterNot { it.id in roomIds }.map(::recoverLegacyLocalFile)
            if (legacy.isNotEmpty()) dao.upsertAll(legacy.map(DownloadEntry::toEntity))
            preferences.edit {
                putBoolean(KEY_ROOM_MIGRATED, true)
                remove(KEY_ENTRIES)
            }
        }
        reconcileCompletedFilesLocked()
    }

    suspend fun get(id: String): DownloadEntry? = dao.get(id)?.toModel()

    suspend fun getAll(): List<DownloadEntry> = dao.getAll().map(DownloadEntity::toModel)

    fun snapshot(id: String): DownloadEntry? = entries.value.firstOrNull { it.id == id }

    suspend fun findLogical(providerId: String, releaseId: String, episodeId: String): DownloadEntry? =
        dao.getLogicalEpisode(providerId, releaseId, episodeId)?.toModel()

    suspend fun put(entry: DownloadEntry, source: DownloadMediaSource? = null) = mutationMutex.withLock {
        source?.let { secureStore.put(mediaKey(entry.id), encodeMediaSource(it)) }
        dao.deleteOtherVariants(entry.providerId, entry.releaseId, entry.episodeId, entry.id)
        dao.upsert(entry.toEntity())
    }

    suspend fun update(id: String, transform: (DownloadEntry) -> DownloadEntry): DownloadEntry? =
        mutationMutex.withLock {
        val existing = dao.get(id)?.toModel() ?: return@withLock null
        val updated = transform(existing)
        dao.upsert(updated.toEntity())
        updated
    }

    suspend fun updateMediaSource(id: String, operationToken: String, source: DownloadMediaSource): Boolean =
        mutationMutex.withLock {
        if (dao.get(id)?.toModel()?.belongsToOperation(operationToken) != true) return@withLock false
        secureStore.put(mediaKey(id), encodeMediaSource(source))
        true
    }

    suspend fun remove(id: String) = mutationMutex.withLock {
        dao.delete(id)
        secureStore.put(mediaKey(id), null)
    }

    fun mediaSource(id: String): DownloadMediaSource? = secureStore.get(mediaKey(id))
        ?.let(::decodeMediaSource)

    private suspend fun reconcileCompletedFilesLocked() {
        dao.getAll().forEach { row ->
            val entry = row.toModel()
            if (entry.status != DownloadStatus.COMPLETED) return@forEach
            val file = entry.localFilePath?.let(::File)
            if (file?.isFile == true && file.length() > 0L) return@forEach
            dao.upsert(
                entry.copy(
                    status = DownloadStatus.MISSING,
                    progressPercent = 0f,
                    localFilePath = null,
                    diagnosticStage = "Файл отсутствует после восстановления",
                    errorMessage = "Скачайте серию повторно",
                    updatedAt = System.currentTimeMillis(),
                ).toEntity(),
            )
        }
    }

    private fun readLegacyEntries(): List<DownloadEntry> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toEntry()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun recoverLegacyLocalFile(entry: DownloadEntry): DownloadEntry {
        if (entry.localFilePath != null || entry.status != DownloadStatus.COMPLETED) return entry
        val directory = java.io.File(appContext.filesDir, "downloads")
        val file = listOf("mp4", "ts", "m4v", "mov")
            .asSequence()
            .map { extension -> java.io.File(directory, "${entry.id}.$extension") }
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: return entry.copy(
                status = DownloadStatus.FAILED,
                diagnosticStage = "Локальный файл предыдущей загрузки не найден",
                errorMessage = "Повторите загрузку",
            )
        return entry.copy(
            localFilePath = file.absolutePath,
            localMimeType = if (file.extension.equals("ts", true)) "video/mp2t" else "video/mp4",
            bytesDownloaded = file.length(),
            contentLength = file.length(),
            diagnosticStage = "Восстановлено из предыдущей версии",
        )
    }

    private fun JSONObject.toEntry(): DownloadEntry? = runCatching {
        DownloadEntry(
            id = getString("id"),
            providerId = getString("providerId"),
            providerName = getString("providerName"),
            releaseId = getString("releaseId"),
            releaseName = getString("releaseName"),
            episodeId = getString("episodeId"),
            episodeOrdinal = optDoubleOrNull("episodeOrdinal"),
            episodeName = optStringOrNull("episodeName"),
            quality = optIntOrNull("quality"),
            translation = optStringOrNull("translation"),
            translationKey = optStringOrNull("translationKey"),
            sourceName = optStringOrNull("sourceName"),
            streamType = OnlineStreamType.valueOf(getString("streamType")),
            streamProviderId = optStringOrNull("streamProviderId"),
            streamProviderName = optStringOrNull("streamProviderName"),
            sourceHost = optStringOrNull("sourceHost"),
            status = runCatching { DownloadStatus.valueOf(getString("status")) }.getOrDefault(DownloadStatus.FAILED),
            progressPercent = optDouble("progressPercent", 0.0).toFloat().coerceIn(0f, 100f),
            bytesDownloaded = optLong("bytesDownloaded", 0L),
            contentLength = optLong("contentLength", -1L),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            operationToken = optStringOrNull("operationToken"),
            localFilePath = optStringOrNull("localFilePath"),
            localMimeType = optStringOrNull("localMimeType"),
            completedItems = optInt("completedItems", 0),
            totalItems = optInt("totalItems", 0),
            speedBytesPerSecond = optLong("speedBytesPerSecond", 0L),
            etaSeconds = if (has("etaSeconds") && !isNull("etaSeconds")) optLong("etaSeconds") else null,
            attemptCount = optInt("attemptCount", 0),
            failureKind = optStringOrNull("failureKind")?.let { runCatching { DownloadFailureKind.valueOf(it) }.getOrNull() },
            diagnosticStage = optStringOrNull("diagnosticStage"),
            errorMessage = optStringOrNull("errorMessage"),
        )
    }.getOrNull()

    private fun encodeMediaSource(source: DownloadMediaSource): String = JSONObject().apply {
        put("url", source.url)
        put("headers", JSONObject(source.headers))
        source.providerId?.let { put("providerId", it) }
        source.providerName?.let { put("providerName", it) }
        source.streamType?.let { put("streamType", it.name) }
        source.quality?.let { put("quality", it) }
        source.translation?.let { put("translation", it) }
        source.translationKey?.let { put("translationKey", it) }
        source.sourceName?.let { put("sourceName", it) }
        source.expiresAtEpochMs?.let { put("expiresAtEpochMs", it) }
        put("refreshable", source.refreshable)
    }.toString()

    private fun decodeMediaSource(raw: String): DownloadMediaSource? = runCatching {
        val json = JSONObject(raw)
        val headersJson = json.optJSONObject("headers") ?: JSONObject()
        val headers = buildMap {
            val keys = headersJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headersJson.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
            }
        }
        DownloadMediaSource(
            url = json.getString("url"),
            headers = headers,
            providerId = json.optStringOrNull("providerId"),
            providerName = json.optStringOrNull("providerName"),
            streamType = json.optStringOrNull("streamType")?.let { runCatching { OnlineStreamType.valueOf(it) }.getOrNull() },
            quality = json.optIntOrNull("quality"),
            translation = json.optStringOrNull("translation"),
            translationKey = json.optStringOrNull("translationKey"),
            sourceName = json.optStringOrNull("sourceName"),
            expiresAtEpochMs = if (json.has("expiresAtEpochMs") && !json.isNull("expiresAtEpochMs")) json.optLong("expiresAtEpochMs") else null,
            refreshable = if (json.has("refreshable")) json.optBoolean("refreshable", true) else true,
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private companion object {
        const val PREFERENCES_NAME = "offline_downloads_v1"
        const val KEY_ENTRIES = "entries"
        const val KEY_ROOM_MIGRATED = "room_migrated_v5"
        fun mediaKey(id: String) = "download.media.$id"
    }
}

private fun DownloadEntry.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    providerId = providerId,
    providerName = providerName,
    releaseId = releaseId,
    releaseName = releaseName,
    episodeId = episodeId,
    episodeOrdinal = episodeOrdinal,
    episodeName = episodeName,
    quality = quality,
    translation = translation,
    translationKey = translationKey,
    sourceName = sourceName,
    streamType = streamType.name,
    streamProviderId = streamProviderId,
    streamProviderName = streamProviderName,
    sourceHost = sourceHost,
    status = status.name,
    progressPercent = progressPercent,
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    createdAt = createdAt,
    updatedAt = updatedAt,
    operationToken = operationToken,
    localFilePath = localFilePath,
    localMimeType = localMimeType,
    localEpisodeId = localEpisodeId,
    completedItems = completedItems,
    totalItems = totalItems,
    speedBytesPerSecond = speedBytesPerSecond,
    etaSeconds = etaSeconds,
    attemptCount = attemptCount,
    failureKind = failureKind?.name,
    diagnosticStage = diagnosticStage,
    errorMessage = errorMessage,
)

private fun DownloadEntity.toModel(): DownloadEntry = DownloadEntry(
    id = id,
    providerId = providerId,
    providerName = providerName,
    releaseId = releaseId,
    releaseName = releaseName,
    episodeId = episodeId,
    episodeOrdinal = episodeOrdinal,
    episodeName = episodeName,
    quality = quality,
    translation = translation,
    translationKey = translationKey,
    sourceName = sourceName,
    streamType = runCatching { OnlineStreamType.valueOf(streamType) }.getOrDefault(OnlineStreamType.MP4),
    streamProviderId = streamProviderId,
    streamProviderName = streamProviderName,
    sourceHost = sourceHost,
    status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
    progressPercent = progressPercent.coerceIn(0f, 100f),
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    createdAt = createdAt,
    updatedAt = updatedAt,
    operationToken = operationToken,
    localFilePath = localFilePath,
    localMimeType = localMimeType,
    localEpisodeId = localEpisodeId,
    completedItems = completedItems,
    totalItems = totalItems,
    speedBytesPerSecond = speedBytesPerSecond,
    etaSeconds = etaSeconds,
    attemptCount = attemptCount,
    failureKind = failureKind?.let { runCatching { DownloadFailureKind.valueOf(it) }.getOrNull() },
    diagnosticStage = diagnosticStage,
    errorMessage = errorMessage,
)
