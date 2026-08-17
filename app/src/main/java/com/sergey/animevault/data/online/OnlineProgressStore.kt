package com.sergey.animevault.data.online

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OnlineProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow(loadProgress(preferences))
    val progress: StateFlow<Map<String, OnlineWatchProgress>> = _progress.asStateFlow()

    init {
        migrateLegacyProgress()
        repairJutSuProgress()
    }

    fun get(providerId: String, episodeId: String): OnlineWatchProgress =
        _progress.value[progressKey(providerId, episodeId)] ?: OnlineWatchProgress()

    fun forProvider(providerId: String): Map<String, OnlineWatchProgress> = _progress.value
        .filterKeys { it.startsWith("$providerId|") }
        .mapKeys { (key, _) -> key.substringAfter('|') }

    fun save(
        providerId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): OnlineWatchProgress {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(
            minimumValue = 0L,
            maximumValue = safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        val completed = ended || (
            safeDuration > 0L && safePosition >= (safeDuration * COMPLETION_THRESHOLD).toLong()
            )
        val value = OnlineWatchProgress(
            positionMs = if (completed) 0L else safePosition,
            durationMs = safeDuration,
            isCompleted = completed,
            lastWatchedAt = System.currentTimeMillis(),
        )
        val progressKey = progressKey(providerId, episodeId)
        preferences.edit {
            putLong(key(POSITION_PREFIX, progressKey), value.positionMs)
            putLong(key(DURATION_PREFIX, progressKey), value.durationMs)
            putBoolean(key(COMPLETED_PREFIX, progressKey), value.isCompleted)
            putLong(key(WATCHED_PREFIX, progressKey), value.lastWatchedAt)
        }
        _progress.value = _progress.value.toMutableMap().apply { put(progressKey, value) }
        return value
    }

    fun clear() {
        preferences.edit { clear() }
        legacyPreferences.edit { clear() }
        _progress.value = emptyMap()
    }

    fun snapshot(): Map<String, OnlineWatchProgress> = _progress.value.toMap()

    @Synchronized
    fun restore(snapshot: Map<String, OnlineWatchProgress>) {
        if (snapshot.isEmpty()) return
        val merged = _progress.value.toMutableMap()
        preferences.edit {
            snapshot.forEach { (progressKey, raw) ->
                val value = raw.copy(
                    positionMs = raw.positionMs.coerceAtLeast(0L),
                    durationMs = raw.durationMs.coerceAtLeast(0L),
                    lastWatchedAt = raw.lastWatchedAt.coerceAtLeast(0L),
                )
                putLong(key(POSITION_PREFIX, progressKey), value.positionMs)
                putLong(key(DURATION_PREFIX, progressKey), value.durationMs)
                putBoolean(key(COMPLETED_PREFIX, progressKey), value.isCompleted)
                putLong(key(WATCHED_PREFIX, progressKey), value.lastWatchedAt)
                merged[progressKey] = value
            }
        }
        _progress.value = merged
    }

    private fun migrateLegacyProgress() {
        if (legacyPreferences.all.isEmpty()) return
        val migrated = loadProgress(legacyPreferences)
        if (migrated.isEmpty()) return
        migrated.forEach { (episodeId, value) ->
            val progressKey = progressKey(OnlineProviderIds.ANI_LIBERTY, episodeId)
            preferences.edit {
                putLong(key(POSITION_PREFIX, progressKey), value.positionMs)
                putLong(key(DURATION_PREFIX, progressKey), value.durationMs)
                putBoolean(key(COMPLETED_PREFIX, progressKey), value.isCompleted)
                putLong(key(WATCHED_PREFIX, progressKey), value.lastWatchedAt)
            }
        }
        legacyPreferences.edit { clear() }
        _progress.value = loadProgress(preferences)
    }

    private fun repairJutSuProgress() {
        if (preferences.getBoolean(JUT_SU_REPAIR_KEY, false)) return
        val providerPrefix = "${OnlineProviderIds.JUT_SU}|"
        val progressPrefixes = listOf(POSITION_PREFIX, DURATION_PREFIX, COMPLETED_PREFIX, WATCHED_PREFIX)
        val damagedKeys = preferences.all.keys.filter { storedKey ->
            progressPrefixes.any { prefix -> storedKey.startsWith(prefix + providerPrefix) }
        }
        preferences.edit {
            damagedKeys.forEach { remove(it) }
            putBoolean(JUT_SU_REPAIR_KEY, true)
        }
        _progress.value = loadProgress(preferences)
    }

    private companion object {
        const val PREFERENCES_NAME = "online_progress"
        const val LEGACY_PREFERENCES_NAME = "anilibria_progress"
        const val POSITION_PREFIX = "position."
        const val DURATION_PREFIX = "duration."
        const val COMPLETED_PREFIX = "completed."
        const val WATCHED_PREFIX = "watched."
        const val COMPLETION_THRESHOLD = 0.92
        const val JUT_SU_REPAIR_KEY = "migration.0.3.1.jutsu_progress_repaired"

        fun progressKey(providerId: String, episodeId: String) = "$providerId|$episodeId"
        fun key(prefix: String, progressKey: String) = prefix + progressKey

        fun loadProgress(preferences: SharedPreferences): Map<String, OnlineWatchProgress> =
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(POSITION_PREFIX) }
                .map { it.removePrefix(POSITION_PREFIX) }
                .associateWith { progressKey ->
                    OnlineWatchProgress(
                        positionMs = preferences.getLong(key(POSITION_PREFIX, progressKey), 0L),
                        durationMs = preferences.getLong(key(DURATION_PREFIX, progressKey), 0L),
                        isCompleted = preferences.getBoolean(key(COMPLETED_PREFIX, progressKey), false),
                        lastWatchedAt = preferences.getLong(key(WATCHED_PREFIX, progressKey), 0L),
                    )
                }
    }
}
