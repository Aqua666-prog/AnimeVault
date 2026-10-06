package com.sergey.animevault.data.playback

/**
 * Determines when the resolver for the next episode should be warmed.
 *
 * The window is deliberately short because many anime CDNs return signed URLs. Resolving several
 * minutes early would make the prefetch more likely to expire before autoplay than to help it.
 */
object PlaybackPrefetchPolicy {
    const val PREFETCH_REMAINING_MS: Long = 30_000L
    const val PREFETCH_FRACTION: Double = 0.97
    const val MIN_DURATION_FOR_FRACTION_MS: Long = 2 * 60_000L

    fun shouldPrefetchNextEpisode(
        positionMs: Long,
        durationMs: Long,
        hasNextEpisode: Boolean,
        alreadyPrefetched: Boolean,
    ): Boolean {
        if (!hasNextEpisode || alreadyPrefetched || durationMs <= 0L) return false
        val safePosition = positionMs.coerceIn(0L, durationMs)
        val remaining = durationMs - safePosition
        if (remaining <= PREFETCH_REMAINING_MS) return true
        if (durationMs < MIN_DURATION_FOR_FRACTION_MS) return false
        return safePosition.toDouble() / durationMs.toDouble() >= PREFETCH_FRACTION
    }
}
