package com.sergey.animevault.data.online

/**
 * Shared source ranking used by the unified provider before playback preferences are applied.
 * Playback health dominates, then provider priority, direct/native transport and quality.
 */
internal object ProviderStreamRanker {
    fun score(
        stream: OnlineStream,
        health: ProviderHealthState?,
        providerPriority: Int = 0,
    ): Int {
        val healthPoints = health?.healthScoreFor(ProviderHealthChannel.PLAYBACK) ?: 50
        val directPoints = when (stream.type) {
            OnlineStreamType.HLS -> 16
            OnlineStreamType.MP4 -> 14
            OnlineStreamType.EMBED -> 0
        }
        val qualityPoints = ((stream.quality ?: 0) / 120).coerceIn(0, 12)
        val channel = health?.channel(ProviderHealthChannel.PLAYBACK)
        val failurePenalty = (channel?.consecutiveFailures ?: 0).coerceAtMost(3) * 6
        val cooldownPenalty = if ((channel?.cooldownUntilMs ?: 0L) > System.currentTimeMillis()) 35 else 0
        val priorityPoints = (providerPriority / 10).coerceIn(-10, 10)
        val expiryPenalty = stream.expiresAtEpochMs
            ?.takeIf { it <= System.currentTimeMillis() + 60_000L }
            ?.let { 25 }
            ?: 0
        return (healthPoints + directPoints + qualityPoints + priorityPoints - failurePenalty - cooldownPenalty - expiryPenalty)
            .coerceIn(0, 125)
    }

    fun downloadScore(
        stream: OnlineStream,
        health: ProviderHealthState?,
        providerPriority: Int = 0,
    ): Int {
        if (stream.type == OnlineStreamType.EMBED) return 0
        val healthPoints = health?.healthScoreFor(ProviderHealthChannel.DOWNLOAD) ?: 50
        val transportPoints = when (stream.type) {
            OnlineStreamType.MP4 -> 18
            OnlineStreamType.HLS -> 16
            OnlineStreamType.EMBED -> 0
        }
        val qualityPoints = ((stream.quality ?: 0) / 120).coerceIn(0, 12)
        val channel = health?.channel(ProviderHealthChannel.DOWNLOAD)
        val failurePenalty = (channel?.consecutiveFailures ?: 0).coerceAtMost(3) * 8
        val cooldownPenalty = if ((channel?.cooldownUntilMs ?: 0L) > System.currentTimeMillis()) 40 else 0
        val priorityPoints = (providerPriority / 10).coerceIn(-10, 10)
        return (healthPoints + transportPoints + qualityPoints + priorityPoints - failurePenalty - cooldownPenalty)
            .coerceIn(0, 130)
    }
}
