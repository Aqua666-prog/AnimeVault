package com.sergey.animevault.data.download

import android.content.Context
import androidx.core.content.edit
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Room-backed download state with one-time migration from the old JSON store. */
class DownloadStore(
    context: Context,
    private val dao: DownloadDao,
) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureStore = SecureSessionStore(context)
    private val _entries: MutableStateFlow<List<DownloadEntry>> = sharedState ?: synchronized(LOCK) {
        sharedState ?: MutableStateFlow(loadAndMigrate()).also { sharedState = it }
    }
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    fun get(id: String): DownloadEntry? = synchronized(LOCK) {
        _entries.value.firstOrNull { it.id == id }
    }

    fun put(entry: DownloadEntry, source: DownloadMediaSource? = null) = synchronized(LOCK) {
        source?.let { secureStore.put(mediaKey(entry.id), encodeMediaSource(it)) }
        persistEntry(entry)
        publish(_entries.value.filterNot { it.id == entry.id } + entry)
    }

    fun update(id: String, transform: (DownloadEntry) -> DownloadEntry): DownloadEntry? = synchronized(LOCK) {
        val existing = _entries.value.firstOrNull { it.id == id } ?: return@synchronized null
        val updated = transform(existing)
        persistEntry(updated)
        publish(_entries.value.map { if (it.id == id) updated else it })
        updated
    }

    fun updateMediaSource(id: String, operationToken: String, source: DownloadMediaSource): Boolean = synchronized(LOCK) {
        if (_entries.value.none { it.id == id && it.belongsToOperation(operationToken) }) return@synchronized false
        secureStore.put(mediaKey(id), encodeMediaSource(source))
        true
    }

    fun remove(id: String) = synchronized(LOCK) {
        blocking { dao.delete(id) }
        secureStore.put(mediaKey(id), null)
        publish(_entries.value.filterNot { it.id == id })
    }

    fun mediaSource(id: String): DownloadMediaSource? = secureStore.get(mediaKey(id))
        ?.let(::decodeMediaSource)

    private fun loadAndMigrate(): List<DownloadEntry> {
        val roomEntries = blocking { dao.getAll() }.map(DownloadEntity::toModel)
        if (preferences.getBoolean(KEY_ROOM_MIGRATED, false)) return roomEntries

        val roomIds = roomEntries.mapTo(mutableSetOf(), DownloadEntry::id)
        val legacy = readLegacyEntries().filterNot { it.id in roomIds }.map(::recoverLegacyLocalFile)
        if (legacy.isNotEmpty()) blocking { dao.upsertAll(legacy.map(DownloadEntry::toEntity)) }
        preferences.edit {
            putBoolean(KEY_ROOM_MIGRATED, true)
            remove(KEY_ENTRIES)
        }
        return (roomEntries + legacy).sortedByDescending(DownloadEntry::createdAt)
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

    private fun persistEntry(entry: DownloadEntry) {
        blocking { dao.upsert(entry.toEntity()) }
    }

    private fun publish(entries: List<DownloadEntry>) {
        _entries.value = entries.sortedByDescending(DownloadEntry::createdAt)
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
            diagnosticStage = optStringOrNull("diagnosticStage"),
            errorMessage = optStringOrNull("errorMessage"),
        )
    }.getOrNull()

    private fun encodeMediaSource(source: DownloadMediaSource): String = JSONObject().apply {
        put("url", source.url)
        put("headers", JSONObject(source.headers))
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
        DownloadMediaSource(url = json.getString("url"), headers = headers)
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private companion object {
        @Volatile var sharedState: MutableStateFlow<List<DownloadEntry>>? = null
        val LOCK = Any()
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
    status = status.name,
    progressPercent = progressPercent,
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    createdAt = createdAt,
    updatedAt = updatedAt,
    operationToken = operationToken,
    localFilePath = localFilePath,
    localMimeType = localMimeType,
    completedItems = completedItems,
    totalItems = totalItems,
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
    status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
    progressPercent = progressPercent.coerceIn(0f, 100f),
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    createdAt = createdAt,
    updatedAt = updatedAt,
    operationToken = operationToken,
    localFilePath = localFilePath,
    localMimeType = localMimeType,
    completedItems = completedItems,
    totalItems = totalItems,
    diagnosticStage = diagnosticStage,
    errorMessage = errorMessage,
)
