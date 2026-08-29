package com.sergey.animevault.data.online

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sergey.animevault.data.playback.PlaybackCompletionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-backed online progress with a one-time SharedPreferences import. */
class OnlineProgressStore(
    context: Context,
    private val dao: OnlineStateDao,
    scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyInitial = readLegacySnapshot()
    private val mutex = Mutex()
    val progress: StateFlow<Map<String, OnlineWatchProgress>> = dao.observeProgress()
        .map { rows -> rows.associate { progressKey(it.providerId, it.episodeId) to it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, legacyInitial)

    suspend fun initialize() = mutex.withLock {
        if (preferences.getBoolean(ROOM_MIGRATED_KEY, false)) return@withLock
        val existing = dao.getAllProgress().associateBy { progressKey(it.providerId, it.episodeId) }
        val imported = legacyInitial.mapNotNull { (key, value) ->
            val providerId = key.substringBefore('|', "")
            val episodeId = key.substringAfter('|', "")
            if (providerId.isBlank() || episodeId.isBlank() || key in existing) null
            else value.toEntity(providerId, episodeId)
        }
        if (imported.isNotEmpty()) dao.upsertProgress(imported)
        preferences.edit { clear(); putBoolean(ROOM_MIGRATED_KEY, true) }
        legacyPreferences.edit { clear() }
    }

    fun get(providerId: String, episodeId: String): OnlineWatchProgress =
        progress.value[progressKey(providerId, episodeId)] ?: OnlineWatchProgress()

    fun forProvider(providerId: String): Map<String, OnlineWatchProgress> = progress.value
        .filterKeys { it.startsWith("$providerId|") }
        .mapKeys { (key, _) -> key.substringAfter('|') }

    suspend fun save(
        providerId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): OnlineWatchProgress = mutex.withLock {
        val normalized = PlaybackCompletionPolicy.normalize(positionMs, durationMs, ended)
        val previous = dao.getProgress(providerId, episodeId)?.toModel() ?: OnlineWatchProgress()
        val startingPlayback = normalized.positionMs > 0L &&
            (previous.lastWatchedAt <= 0L || previous.isCompleted || previous.positionMs <= 0L)
        val value = OnlineWatchProgress(
            positionMs = normalized.positionMs,
            durationMs = normalized.durationMs,
            isCompleted = normalized.isCompleted,
            lastWatchedAt = normalized.lastWatchedAt,
            firstPlayedAt = previous.firstPlayedAt.takeIf { it > 0L } ?: normalized.lastWatchedAt,
            completedAt = if (normalized.isCompleted) previous.completedAt ?: normalized.lastWatchedAt else null,
            playCount = (previous.playCount + if (startingPlayback) 1 else 0).coerceAtLeast(1),
        )
        dao.upsertProgress(value.toEntity(providerId, episodeId))
        value
    }

    suspend fun clear() = mutex.withLock {
        dao.clearProgress()
        preferences.edit { clear(); putBoolean(ROOM_MIGRATED_KEY, true) }
        legacyPreferences.edit { clear() }
    }

    fun snapshot(): Map<String, OnlineWatchProgress> = progress.value.toMap()

    suspend fun restore(snapshot: Map<String, OnlineWatchProgress>) = mutex.withLock {
        val entities = snapshot.mapNotNull { (key, raw) ->
            val providerId = key.substringBefore('|', "")
            val episodeId = key.substringAfter('|', "")
            if (providerId.isBlank() || episodeId.isBlank()) return@mapNotNull null
            raw.copy(
                positionMs = raw.positionMs.coerceAtLeast(0L),
                durationMs = raw.durationMs.coerceAtLeast(0L),
                lastWatchedAt = raw.lastWatchedAt.coerceAtLeast(0L),
                firstPlayedAt = raw.firstPlayedAt.coerceAtLeast(0L),
                completedAt = raw.completedAt?.coerceAtLeast(0L),
                playCount = raw.playCount.coerceAtLeast(0),
            ).toEntity(providerId, episodeId)
        }
        if (entities.isNotEmpty()) dao.upsertProgress(entities)
    }

    private fun readLegacySnapshot(): Map<String, OnlineWatchProgress> {
        val current = loadProgress(preferences).filterKeys { !it.startsWith("${OnlineProviderIds.JUT_SU}|") }
        val legacy = loadProgress(legacyPreferences).mapKeys { (episodeId, _) ->
            progressKey(OnlineProviderIds.ANI_LIBERTY, episodeId)
        }
        return current + legacy
    }

    private companion object {
        const val PREFERENCES_NAME = "online_progress"
        const val LEGACY_PREFERENCES_NAME = "anilibria_progress"
        const val ROOM_MIGRATED_KEY = "room_migrated_v7"
        const val POSITION_PREFIX = "position."
        const val DURATION_PREFIX = "duration."
        const val COMPLETED_PREFIX = "completed."
        const val WATCHED_PREFIX = "watched."
        const val FIRST_PLAYED_PREFIX = "first_played."
        const val COMPLETED_AT_PREFIX = "completed_at."
        const val PLAY_COUNT_PREFIX = "play_count."

        fun progressKey(providerId: String, episodeId: String) = "$providerId|$episodeId"
        fun key(prefix: String, progressKey: String) = prefix + progressKey

        fun loadProgress(preferences: SharedPreferences): Map<String, OnlineWatchProgress> =
            preferences.all.keys.asSequence()
                .filter { it.startsWith(POSITION_PREFIX) }
                .map { it.removePrefix(POSITION_PREFIX) }
                .associateWith { storedKey ->
                    val watched = preferences.getLong(key(WATCHED_PREFIX, storedKey), 0L)
                    OnlineWatchProgress(
                        positionMs = preferences.getLong(key(POSITION_PREFIX, storedKey), 0L),
                        durationMs = preferences.getLong(key(DURATION_PREFIX, storedKey), 0L),
                        isCompleted = preferences.getBoolean(key(COMPLETED_PREFIX, storedKey), false),
                        lastWatchedAt = watched,
                        firstPlayedAt = preferences.getLong(key(FIRST_PLAYED_PREFIX, storedKey), watched),
                        completedAt = preferences.getLong(key(COMPLETED_AT_PREFIX, storedKey), -1L).takeIf { it >= 0L },
                        playCount = preferences.getInt(key(PLAY_COUNT_PREFIX, storedKey), 0)
                            .takeIf { it > 0 } ?: if (watched > 0L) 1 else 0,
                    )
                }
    }
}
