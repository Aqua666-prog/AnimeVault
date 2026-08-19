package com.sergey.animevault.data.online

/**
 * Shared source ranking used by the unified provider before playback preferences are applied.
 * Health dominates, then native/direct playback and quality break close ties.
 */
internal object ProviderStreamRanker {
    fun score(
        stream: OnlineStream,
        health: ProviderHealthState?,
        providerPriority: Int = 0,
    ): Int {
        val healthPoints = health?.healthScore ?: 50
        val directPoints = when (stream.type) {
            OnlineStreamType.HLS -> 14
            OnlineStreamType.MP4 -> 12
            OnlineStreamType.EMBED -> 0
        }
        val qualityPoints = ((stream.quality ?: 0) / 120).coerceIn(0, 12)
        val failurePenalty = (health?.consecutiveFailures ?: 0).coerceAtMost(3) * 6
        val cooldownPenalty = if ((health?.cooldownUntilMs ?: 0L) > System.currentTimeMillis()) 35 else 0
        val priorityPoints = (providerPriority / 10).coerceIn(-10, 10)
        return (healthPoints + directPoints + qualityPoints + priorityPoints - failurePenalty - cooldownPenalty)
            .coerceIn(0, 125)
    }
}
