package com.sergey.animevault.ui.home

/**
 * Normalized continue-watching item used by the Home screen.
 *
 * Offline Room progress and online provider progress deliberately meet only at
 * this presentation boundary. Neither storage system has to know about the
 * other, which keeps Home a read-only aggregation layer.
 */
sealed interface HomeContinueItem {
    val stableKey: String
    val title: String
    val posterUri: String?
    val lastWatchedAt: Long
    val progressFraction: Float

    data class Local(
        val episodeId: Long,
        val titleId: Long,
        override val title: String,
        override val posterUri: String?,
        val episodeNumber: Double?,
        val seasonNumber: Int?,
        val positionMs: Long,
        val durationMs: Long,
        override val lastWatchedAt: Long,
    ) : HomeContinueItem {
        override val stableKey: String = "local:$episodeId"
        override val progressFraction: Float = progressFraction(positionMs, durationMs)
    }

    data class Online(
        val providerId: String,
        val releaseId: String,
        val episodeId: String,
        override val title: String,
        override val posterUri: String?,
        val episodeOrdinal: Double?,
        val providerName: String,
        val positionMs: Long,
        val durationMs: Long,
        override val lastWatchedAt: Long,
    ) : HomeContinueItem {
        override val stableKey: String = "online:$providerId:$releaseId:$episodeId"
        override val progressFraction: Float = progressFraction(positionMs, durationMs)
    }
}

internal fun rankContinueItems(
    items: List<HomeContinueItem>,
    limit: Int = HOME_CONTINUE_LIMIT,
): List<HomeContinueItem> {
    if (limit <= 0) return emptyList()
    return items
        .asSequence()
        .filter { item -> item.lastWatchedAt > 0L }
        .sortedWith(
            compareByDescending<HomeContinueItem> { it.lastWatchedAt }
                .thenBy { it.stableKey },
        )
        .take(limit)
        .toList()
}

internal fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (positionMs <= 0L || durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

internal const val HOME_CONTINUE_LIMIT = 12
internal const val HOME_RECENT_LIMIT = 12
internal const val HOME_FAVORITES_LIMIT = 12
