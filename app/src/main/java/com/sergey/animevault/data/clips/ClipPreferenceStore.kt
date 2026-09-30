package com.sergey.animevault.data.clips

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar
import java.util.Locale
import kotlin.math.ln

/**
 * Small on-device recommendation profile for the clips feed.
 *
 * Only coarse interaction weights are stored locally. Nothing is uploaded.
 * A deterministic exploration bonus remains in the ranking so the feed can
 * still surface genres outside the strongest preference cluster.
 */
class ClipPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun score(
        itemKey: String,
        genres: List<String>,
        year: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        val affinityValues = genres.map(::genreAffinity)
        val genreAffinity = affinityValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val seenCount = preferences.getInt(SEEN_COUNT_PREFIX + itemKey, 0).coerceAtLeast(0)
        val lastSeenAt = preferences.getLong(LAST_SEEN_PREFIX + itemKey, 0L)
        val hoursSinceSeen = if (lastSeenAt <= 0L) {
            Long.MAX_VALUE
        } else {
            ((nowMs - lastSeenAt).coerceAtLeast(0L) / 3_600_000L)
        }
        val recentPenalty = when {
            hoursSinceSeen < 6L -> 4.0
            hoursSinceSeen < 24L -> 2.4
            hoursSinceSeen < 24L * 7L -> 1.0
            else -> 0.0
        }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val freshness = when {
            year == null -> 0.0
            year >= currentYear - 1 -> 0.8
            year >= currentYear - 4 -> 0.35
            else -> 0.0
        }
        val seenPenalty = ln(1.0 + seenCount.toDouble()) * 1.6
        return genreAffinity * 1.8 +
            freshness +
            stableExplorationBonus(itemKey) -
            seenPenalty -
            recentPenalty
    }

    fun recordImpression(itemKey: String) {
        val countKey = SEEN_COUNT_PREFIX + itemKey
        val nextCount = preferences.getInt(countKey, 0).coerceAtLeast(0) + 1
        preferences.edit {
            putInt(countKey, nextCount)
            putLong(LAST_SEEN_PREFIX + itemKey, System.currentTimeMillis())
        }
    }

    fun recordWatch(genres: List<String>, durationMs: Long) {
        val delta = when {
            durationMs < QUICK_SKIP_MS -> -0.35
            durationMs >= STRONG_WATCH_MS -> 0.30
            durationMs >= LIGHT_WATCH_MS -> 0.10
            else -> 0.0
        }
        adjustGenres(genres, delta)
    }

    fun recordOpened(genres: List<String>) = adjustGenres(genres, 0.85)

    fun recordFavorite(genres: List<String>, favorite: Boolean) =
        adjustGenres(genres, if (favorite) 1.75 else -0.45)

    fun recordPlayback(genres: List<String>) = adjustGenres(genres, 2.25)

    fun recordNotInterested(genres: List<String>) = adjustGenres(genres, -2.1)

    private fun genreAffinity(genre: String): Double =
        preferences.getFloat(GENRE_PREFIX + normalizeGenre(genre), 0f).toDouble()

    private fun adjustGenres(genres: List<String>, delta: Double) {
        if (delta == 0.0 || genres.isEmpty()) return
        val cleanGenres = genres
            .map(::normalizeGenre)
            .filter(String::isNotBlank)
            .distinct()
        if (cleanGenres.isEmpty()) return
        preferences.edit {
            cleanGenres.forEach { genre ->
                val key = GENRE_PREFIX + genre
                val current = preferences.getFloat(key, 0f).toDouble()
                putFloat(key, (current + delta).coerceIn(MIN_AFFINITY, MAX_AFFINITY).toFloat())
            }
        }
    }

    private fun normalizeGenre(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\s+"), " ")

    private companion object {
        const val PREFERENCES_NAME = "clip_feed_preferences"
        const val GENRE_PREFIX = "genre."
        const val SEEN_COUNT_PREFIX = "seen."
        const val LAST_SEEN_PREFIX = "last_seen."
        const val QUICK_SKIP_MS = 1_800L
        const val LIGHT_WATCH_MS = 5_000L
        const val STRONG_WATCH_MS = 12_000L
        const val MIN_AFFINITY = -8.0
        const val MAX_AFFINITY = 8.0
    }
}

internal fun stableExplorationBonus(itemKey: String): Double {
    val positive = itemKey.hashCode().toLong() and 0x7fff_ffffL
    return (positive % 1_000L).toDouble() / 1_000.0 * 1.6
}
