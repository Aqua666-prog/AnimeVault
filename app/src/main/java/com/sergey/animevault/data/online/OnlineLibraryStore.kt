package com.sergey.animevault.data.online

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Lightweight persistent library for online releases.
 *
 * Room is the source of truth for online favourites and playback history.
 * SharedPreferences are read only once to migrate installations from older versions.
 */
data class OnlineLibraryEntry(
    val providerId: String,
    val providerName: String,
    val releaseId: String,
    val name: String,
    val englishName: String? = null,
    val posterUrl: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val season: String? = null,
    val episodeCount: Int? = null,
    val isOngoing: Boolean = false,
    val isFavorite: Boolean = false,
    val favoriteAddedAt: Long = 0L,
    val firstOpenedAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
    val lastWatchedAt: Long = 0L,
    val lastEpisodeId: String? = null,
    val lastEpisodeOrdinal: Double? = null,
    val lastPositionMs: Long = 0L,
    val lastDurationMs: Long = 0L,
    val lastEpisodeCompleted: Boolean = false,
) {
    val hasHistory: Boolean
        get() = lastOpenedAt > 0L || lastWatchedAt > 0L

    val hasContinueProgress: Boolean
        get() = lastEpisodeId != null && !lastEpisodeCompleted && lastPositionMs > 0L

    val progressFraction: Float
        get() = when {
            lastEpisodeCompleted -> 1f
            lastDurationMs <= 0L -> 0f
            else -> (lastPositionMs.toFloat() / lastDurationMs.toFloat()).coerceIn(0f, 1f)
        }

    fun toReleaseCard(): OnlineReleaseCard = OnlineReleaseCard(
        providerId = providerId,
        providerName = providerName,
        id = releaseId,
        alias = releaseId,
        name = name,
        englishName = englishName,
        posterUrl = posterUrl,
        year = year,
        type = type,
        season = season,
        episodeCount = episodeCount,
        isOngoing = isOngoing,
    )
}

class OnlineLibraryStore(
    context: Context,
    private val dao: OnlineStateDao,
    scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyInitial = loadEntries()
    private val mutex = Mutex()
    val entries: StateFlow<Map<String, OnlineLibraryEntry>> = dao.observeLibrary()
        .map { rows -> rows.associate { entryKey(it.providerId, it.releaseId) to it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, legacyInitial)

    suspend fun initialize() = mutex.withLock {
        if (preferences.getBoolean(ROOM_MIGRATED_KEY, false)) return@withLock
        val existing = dao.getAllLibrary().mapTo(mutableSetOf()) { entryKey(it.providerId, it.releaseId) }
        val imported = legacyInitial.filterKeys { it !in existing }.values.map(OnlineLibraryEntry::toEntity)
        if (imported.isNotEmpty()) dao.upsertLibrary(imported)
        preferences.edit { clear(); putBoolean(ROOM_MIGRATED_KEY, true) }
    }

    suspend fun markOpened(release: OnlineReleaseDetails) {
        val now = System.currentTimeMillis()
        update(release.providerId, release.id) { previous ->
            release.toLibraryEntry(previous).copy(
                isFavorite = previous?.isFavorite == true,
                favoriteAddedAt = previous?.favoriteAddedAt ?: 0L,
                firstOpenedAt = previous?.firstOpenedAt?.takeIf { it > 0L } ?: now,
                lastOpenedAt = now,
                lastWatchedAt = previous?.lastWatchedAt ?: 0L,
                lastEpisodeId = previous?.lastEpisodeId,
                lastEpisodeOrdinal = previous?.lastEpisodeOrdinal,
                lastPositionMs = previous?.lastPositionMs ?: 0L,
                lastDurationMs = previous?.lastDurationMs ?: 0L,
                lastEpisodeCompleted = previous?.lastEpisodeCompleted ?: false,
            )
        }
    }

    suspend fun setFavorite(release: OnlineReleaseDetails, favorite: Boolean) {
        val now = System.currentTimeMillis()
        update(release.providerId, release.id) { previous ->
            release.toLibraryEntry(previous).copy(
                isFavorite = favorite,
                favoriteAddedAt = when {
                    favorite && previous?.isFavorite == true -> previous.favoriteAddedAt
                    favorite -> now
                    else -> 0L
                },
                firstOpenedAt = previous?.firstOpenedAt ?: 0L,
                lastOpenedAt = previous?.lastOpenedAt ?: 0L,
                lastWatchedAt = previous?.lastWatchedAt ?: 0L,
                lastEpisodeId = previous?.lastEpisodeId,
                lastEpisodeOrdinal = previous?.lastEpisodeOrdinal,
                lastPositionMs = previous?.lastPositionMs ?: 0L,
                lastDurationMs = previous?.lastDurationMs ?: 0L,
                lastEpisodeCompleted = previous?.lastEpisodeCompleted ?: false,
            )
        }
        pruneIfEmpty(release.providerId, release.id)
    }

    suspend fun recordPlayback(
        release: OnlineReleaseDetails,
        episode: OnlineEpisode,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        update(release.providerId, release.id) { previous ->
            release.toLibraryEntry(previous).copy(
                isFavorite = previous?.isFavorite == true,
                favoriteAddedAt = previous?.favoriteAddedAt ?: 0L,
                firstOpenedAt = previous?.firstOpenedAt?.takeIf { it > 0L } ?: now,
                lastOpenedAt = maxOf(previous?.lastOpenedAt ?: 0L, now),
                lastWatchedAt = now,
                lastEpisodeId = episode.id,
                lastEpisodeOrdinal = episode.ordinal,
                lastPositionMs = if (completed) 0L else safePosition,
                lastDurationMs = safeDuration,
                lastEpisodeCompleted = completed,
            )
        }
    }

    suspend fun recordDownloadedPlayback(
        providerId: String,
        providerName: String,
        releaseId: String,
        releaseName: String,
        episodeId: String,
        episodeOrdinal: Double?,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        update(providerId, releaseId) { previous ->
            (previous ?: OnlineLibraryEntry(
                providerId = providerId,
                providerName = providerName,
                releaseId = releaseId,
                name = releaseName,
            )).copy(
                providerName = providerName,
                name = releaseName,
                firstOpenedAt = previous?.firstOpenedAt?.takeIf { it > 0L } ?: now,
                lastOpenedAt = maxOf(previous?.lastOpenedAt ?: 0L, now),
                lastWatchedAt = now,
                lastEpisodeId = episodeId,
                lastEpisodeOrdinal = episodeOrdinal,
                lastPositionMs = if (completed) 0L else safePosition,
                lastDurationMs = safeDuration,
                lastEpisodeCompleted = completed,
            )
        }
    }

    suspend fun clearHistory() {
        val updated: Map<String, OnlineLibraryEntry> = buildMap {
            for (row in dao.getAllLibrary()) {
                val entry = row.toModel()
                if (entry.isFavorite) {
                    put(
                        entryKey(entry.providerId, entry.releaseId),
                        entry.copy(
                            firstOpenedAt = 0L,
                            lastOpenedAt = 0L,
                            lastWatchedAt = 0L,
                            lastEpisodeId = null,
                            lastEpisodeOrdinal = null,
                            lastPositionMs = 0L,
                            lastDurationMs = 0L,
                            lastEpisodeCompleted = false,
                        ),
                    )
                }
            }
        }
        persistSnapshot(updated)
    }

    suspend fun clearFavorites() {
        val updated: Map<String, OnlineLibraryEntry> = buildMap {
            for (row in dao.getAllLibrary()) {
                val entry = row.toModel()
                if (entry.hasHistory) {
                    put(
                        entryKey(entry.providerId, entry.releaseId),
                        entry.copy(isFavorite = false, favoriteAddedAt = 0L),
                    )
                }
            }
        }
        persistSnapshot(updated)
    }

    fun get(providerId: String, releaseId: String): OnlineLibraryEntry? =
        entries.value[entryKey(providerId, releaseId)]

    fun snapshot(): List<OnlineLibraryEntry> = entries.value.values.toList()

    suspend fun restore(restoredEntries: List<OnlineLibraryEntry>) {
        if (restoredEntries.isEmpty()) return
        dao.upsertLibrary(restoredEntries.filter { it.providerId.isNotBlank() && it.releaseId.isNotBlank() }.map(OnlineLibraryEntry::toEntity))
        pruneHistoryIfNeeded()
    }

    private fun OnlineReleaseDetails.toLibraryEntry(previous: OnlineLibraryEntry?): OnlineLibraryEntry =
        OnlineLibraryEntry(
            providerId = providerId,
            providerName = providerName,
            releaseId = id,
            name = name,
            englishName = englishName,
            posterUrl = posterUrl,
            year = year,
            type = type,
            season = season,
            episodeCount = episodeCount ?: episodes.size.takeIf { episodes.isNotEmpty() },
            isOngoing = isOngoing,
            isFavorite = previous?.isFavorite ?: false,
            favoriteAddedAt = previous?.favoriteAddedAt ?: 0L,
            firstOpenedAt = previous?.firstOpenedAt ?: 0L,
            lastOpenedAt = previous?.lastOpenedAt ?: 0L,
            lastWatchedAt = previous?.lastWatchedAt ?: 0L,
            lastEpisodeId = previous?.lastEpisodeId,
            lastEpisodeOrdinal = previous?.lastEpisodeOrdinal,
            lastPositionMs = previous?.lastPositionMs ?: 0L,
            lastDurationMs = previous?.lastDurationMs ?: 0L,
            lastEpisodeCompleted = previous?.lastEpisodeCompleted ?: false,
        )

    private suspend fun update(
        providerId: String,
        releaseId: String,
        transform: (OnlineLibraryEntry?) -> OnlineLibraryEntry,
    ) {
        mutex.withLock {
            val previous = dao.getLibrary(providerId, releaseId)?.toModel()
            transform(previous).also { dao.upsertLibrary(it.toEntity()) }
        }
        pruneHistoryIfNeeded()
    }

    private suspend fun pruneHistoryIfNeeded() {
        val removable = dao.getAllLibrary()
            .map(OnlineLibraryEntity::toModel)
            .filter { entry -> entry.hasHistory && !entry.isFavorite }
            .sortedByDescending { entry -> maxOf(entry.lastWatchedAt, entry.lastOpenedAt) }
            .drop(MAX_HISTORY_ENTRIES)
        if (removable.isEmpty()) return

        removable.forEach { entry ->
            dao.deleteLibrary(entry.providerId, entry.releaseId)
        }
    }

    private suspend fun pruneIfEmpty(providerId: String, releaseId: String) {
        val entry = dao.getLibrary(providerId, releaseId)?.toModel() ?: return
        if (entry.isFavorite || entry.hasHistory) return
        dao.deleteLibrary(providerId, releaseId)
    }

    private suspend fun persistSnapshot(snapshot: Map<String, OnlineLibraryEntry>) {
        dao.clearLibrary()
        if (snapshot.isNotEmpty()) dao.upsertLibrary(snapshot.values.map(OnlineLibraryEntry::toEntity))
    }

    private fun loadEntries(): Map<String, OnlineLibraryEntry> = preferences.all
        .asSequence()
        .filter { (key, value) -> key.startsWith(PREFERENCE_ENTRY_PREFIX) && value is String }
        .mapNotNull { (key, value) ->
            val storedKey = key.removePrefix(PREFERENCE_ENTRY_PREFIX)
            runCatching { JSONObject(value as String).toLibraryEntry() }
                .getOrNull()
                ?.let { storedKey to it }
        }
        .toMap()

    private companion object {
        const val PREFERENCES_NAME = "online_library"
        const val PREFERENCE_ENTRY_PREFIX = "entry."
        const val ROOM_MIGRATED_KEY = "room_migrated_v7"
        const val MAX_HISTORY_ENTRIES = 500

        fun entryKey(providerId: String, releaseId: String): String = "$providerId|$releaseId"
    }
}

private fun OnlineLibraryEntry.toJson(): JSONObject = JSONObject().apply {
    put("provider_id", providerId)
    put("provider_name", providerName)
    put("release_id", releaseId)
    put("name", name)
    putNullable("english_name", englishName)
    putNullable("poster_url", posterUrl)
    putNullable("year", year)
    putNullable("type", type)
    putNullable("season", season)
    putNullable("episode_count", episodeCount)
    put("is_ongoing", isOngoing)
    put("is_favorite", isFavorite)
    put("favorite_added_at", favoriteAddedAt)
    put("first_opened_at", firstOpenedAt)
    put("last_opened_at", lastOpenedAt)
    put("last_watched_at", lastWatchedAt)
    putNullable("last_episode_id", lastEpisodeId)
    putNullable("last_episode_ordinal", lastEpisodeOrdinal)
    put("last_position_ms", lastPositionMs)
    put("last_duration_ms", lastDurationMs)
    put("last_episode_completed", lastEpisodeCompleted)
}

private fun JSONObject.toLibraryEntry(): OnlineLibraryEntry = OnlineLibraryEntry(
    providerId = getString("provider_id"),
    providerName = optString("provider_name").ifBlank { optString("provider_id") },
    releaseId = getString("release_id"),
    name = getString("name"),
    englishName = optNullableString("english_name"),
    posterUrl = optNullableString("poster_url"),
    year = optNullableInt("year"),
    type = optNullableString("type"),
    season = optNullableString("season"),
    episodeCount = optNullableInt("episode_count"),
    isOngoing = optBoolean("is_ongoing", false),
    isFavorite = optBoolean("is_favorite", false),
    favoriteAddedAt = optLong("favorite_added_at", 0L),
    firstOpenedAt = optLong("first_opened_at", 0L),
    lastOpenedAt = optLong("last_opened_at", 0L),
    lastWatchedAt = optLong("last_watched_at", 0L),
    lastEpisodeId = optNullableString("last_episode_id"),
    lastEpisodeOrdinal = optNullableDouble("last_episode_ordinal"),
    lastPositionMs = optLong("last_position_ms", 0L),
    lastDurationMs = optLong("last_duration_ms", 0L),
    lastEpisodeCompleted = optBoolean("last_episode_completed", false),
)

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)
