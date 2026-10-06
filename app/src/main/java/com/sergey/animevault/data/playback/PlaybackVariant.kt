package com.sergey.animevault.data.playback

import com.sergey.animevault.data.model.PlaybackEpisodeRow
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.transport.MediaTransportSource
import com.sergey.animevault.data.transport.TransportCandidatePlanner
import com.sergey.animevault.data.transport.TransportFailureKind
import com.sergey.animevault.data.transport.TransportKind
import com.sergey.animevault.data.transport.TransportPreference
import com.sergey.animevault.data.transport.toMediaTransportSource
import com.sergey.animevault.data.transport.transportVariantKey

/**
 * A provider-neutral way to obtain one episode.
 *
 * PlaybackVariant is the player-facing wrapper around the shared transport model. Downloads use
 * the same transport identity/routing rules without depending on Media3 or Compose.
 */
data class PlaybackVariant(
    val key: String,
    val episodeKey: String,
    val uri: String,
    val kind: PlaybackVariantKind,
    val providerId: String? = null,
    val providerName: String? = null,
    val sourceName: String? = null,
    val translation: String? = null,
    val quality: Int? = null,
    val localEpisodeId: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val offlineCacheId: String? = null,
    val hostFamily: String? = null,
    val refreshable: Boolean = false,
    val expiresAtEpochMs: Long? = null,
    val refreshIdentity: String? = null,
    val translationKey: String? = null,
) {
    val isLocal: Boolean get() = kind == PlaybackVariantKind.LOCAL
    val isNativePlayable: Boolean get() = kind != PlaybackVariantKind.EMBED

    val displayName: String
        get() = listOfNotNull(
            if (isLocal) "Локальный файл" else null,
            quality?.let { "${it}p" },
            translation?.takeIf(String::isNotBlank),
            sourceName?.takeIf(String::isNotBlank),
            providerName?.takeIf { it.isNotBlank() && it != sourceName },
        ).distinct().joinToString(" · ").ifBlank {
            when (kind) {
                PlaybackVariantKind.LOCAL -> "Локальный файл"
                PlaybackVariantKind.HLS, PlaybackVariantKind.MP4 -> "Прямой поток"
                PlaybackVariantKind.EMBED -> "Веб-плеер"
                PlaybackVariantKind.EXTERNAL -> "Внешний источник"
            }
        }
}

enum class PlaybackVariantKind {
    LOCAL,
    HLS,
    MP4,
    EMBED,
    EXTERNAL,
}

data class PlaybackVariantPreference(
    val translation: String? = null,
    val quality: Int? = null,
    val sourceName: String? = null,
    val providerId: String? = null,
    val preferLocal: Boolean = true,
)

data class EpisodePlaybackPlan(
    val episodeKey: String,
    val titleKey: String,
    val title: String,
    val episodeTitle: String?,
    val ordinal: Double?,
    val variants: List<PlaybackVariant>,
    val progress: PlaybackProgressSnapshot,
    val nextEpisodeKey: String? = null,
) {
    init {
        require(episodeKey.isNotBlank()) { "episodeKey must not be blank" }
        require(titleKey.isNotBlank()) { "titleKey must not be blank" }
        require(variants.all { it.episodeKey == episodeKey }) {
            "Every playback variant must belong to the same episode"
        }
    }

    val hasPlayableVariant: Boolean get() = variants.any { it.uri.isNotBlank() }
}

/** Player compatibility facade backed by the shared transport candidate planner. */
object PlaybackVariantResolver {
    fun selectPreferred(
        variants: List<PlaybackVariant>,
        preference: PlaybackVariantPreference = PlaybackVariantPreference(),
    ): PlaybackVariant {
        require(variants.isNotEmpty()) { "Для серии нет доступных вариантов воспроизведения" }
        return orderPreferred(variants, preference).first()
    }

    fun orderPreferred(
        variants: List<PlaybackVariant>,
        preference: PlaybackVariantPreference = PlaybackVariantPreference(),
    ): List<PlaybackVariant> {
        val byKey = variants.associateBy(PlaybackVariant::key)
        return TransportCandidatePlanner.orderPreferred(
            sources = variants.map(PlaybackVariant::toTransportSource),
            preference = TransportPreference(
                translation = preference.translation,
                quality = preference.quality,
                sourceName = preference.sourceName,
                providerId = preference.providerId,
                preferLocal = preference.preferLocal,
            ),
        ).mapNotNull { byKey[it.key] }
    }

    fun selectFallback(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        failedVariantKeys: Set<String>,
        failure: PlaybackFailure? = null,
        blockedHostFamilies: Set<String> = emptySet(),
    ): PlaybackVariant? {
        if (failure != null && !PlaybackFallbackPolicy.shouldTryAlternative(failure.kind)) return null
        val byKey = variants.associateBy(PlaybackVariant::key)
        val selected = TransportCandidatePlanner.selectFallback(
            sources = variants.map(PlaybackVariant::toTransportSource),
            current = current.toTransportSource(),
            failedKeys = failedVariantKeys,
            blockedRouteFamilies = blockedHostFamilies,
            failureKind = failure?.kind?.toTransportFailureKind(),
        ) ?: return null
        return byKey[selected.key]
    }
}

object PlaybackFallbackPolicy {
    /** Authentication/configuration failures should be surfaced instead of silently hiding them. */
    fun shouldTryAlternative(kind: PlaybackFailureKind): Boolean = when (kind) {
        PlaybackFailureKind.AUTH_REQUIRED -> false
        else -> true
    }
}

fun PlaybackVariant.toTransportSource(): MediaTransportSource = MediaTransportSource(
    key = key,
    uri = uri,
    kind = kind.toTransportKind(),
    providerId = providerId,
    providerName = providerName,
    sourceName = sourceName,
    translation = translation,
    translationKey = translationKey,
    quality = quality,
    headers = headers,
    offlineCacheId = offlineCacheId,
    routeFamily = hostFamily,
    refreshable = refreshable,
    expiresAtEpochMs = expiresAtEpochMs,
    refreshIdentity = refreshIdentity,
)

fun OnlineStream.toPlaybackVariant(
    episodeKey: String,
    providerId: String,
    providerName: String,
): PlaybackVariant {
    val source = toMediaTransportSource(providerId, providerName)
    return PlaybackVariant(
        key = source.key,
        episodeKey = episodeKey,
        uri = source.uri,
        kind = source.kind.toPlaybackVariantKind(),
        providerId = source.providerId,
        providerName = source.providerName,
        sourceName = source.sourceName,
        translation = source.translation,
        translationKey = source.translationKey,
        quality = source.quality,
        headers = source.headers,
        offlineCacheId = source.offlineCacheId,
        hostFamily = source.normalizedRouteFamily,
        refreshable = source.refreshable,
        expiresAtEpochMs = source.expiresAtEpochMs,
        refreshIdentity = source.stableRefreshIdentity,
    )
}

object OnlineStreamVariantKeys {
    fun keyOf(stream: OnlineStream): String = stream.transportVariantKey()
}

internal fun PlaybackFailureKind.toTransportFailureKind(): TransportFailureKind = when (this) {
    PlaybackFailureKind.TIMEOUT -> TransportFailureKind.TIMEOUT
    PlaybackFailureKind.DNS -> TransportFailureKind.DNS
    PlaybackFailureKind.CONNECTION -> TransportFailureKind.CONNECTION
    PlaybackFailureKind.TLS -> TransportFailureKind.TLS
    PlaybackFailureKind.AUTH_REQUIRED -> TransportFailureKind.AUTH_REQUIRED
    PlaybackFailureKind.FORBIDDEN -> TransportFailureKind.FORBIDDEN
    PlaybackFailureKind.NOT_FOUND -> TransportFailureKind.NOT_FOUND
    PlaybackFailureKind.RATE_LIMITED -> TransportFailureKind.RATE_LIMITED
    PlaybackFailureKind.SERVER -> TransportFailureKind.SERVER
    PlaybackFailureKind.DECODER -> TransportFailureKind.DECODER
    PlaybackFailureKind.UNSUPPORTED_STREAM -> TransportFailureKind.UNSUPPORTED
    PlaybackFailureKind.NETWORK -> TransportFailureKind.NETWORK
    PlaybackFailureKind.UNKNOWN -> TransportFailureKind.UNKNOWN
}

private fun PlaybackVariantKind.toTransportKind(): TransportKind = when (this) {
    PlaybackVariantKind.LOCAL -> TransportKind.LOCAL
    PlaybackVariantKind.HLS -> TransportKind.HLS
    PlaybackVariantKind.MP4 -> TransportKind.MP4
    PlaybackVariantKind.EMBED -> TransportKind.EMBED
    PlaybackVariantKind.EXTERNAL -> TransportKind.EXTERNAL
}

private fun TransportKind.toPlaybackVariantKind(): PlaybackVariantKind = when (this) {
    TransportKind.LOCAL -> PlaybackVariantKind.LOCAL
    TransportKind.HLS -> PlaybackVariantKind.HLS
    TransportKind.MP4 -> PlaybackVariantKind.MP4
    TransportKind.EMBED -> PlaybackVariantKind.EMBED
    TransportKind.EXTERNAL -> PlaybackVariantKind.EXTERNAL
}

fun buildOnlineEpisodePlaybackPlan(
    providerId: String,
    providerName: String,
    releaseId: String,
    releaseName: String,
    episode: OnlineEpisode,
    progress: PlaybackProgressSnapshot,
    nextEpisodeId: String?,
): EpisodePlaybackPlan {
    val episodeKey = "online:$providerId:$releaseId:${episode.id}"
    return EpisodePlaybackPlan(
        episodeKey = episodeKey,
        titleKey = "online:$providerId:$releaseId",
        title = releaseName,
        episodeTitle = episode.name,
        ordinal = episode.ordinal,
        variants = episode.streams.map { stream ->
            stream.toPlaybackVariant(
                episodeKey = episodeKey,
                providerId = providerId,
                providerName = providerName,
            )
        },
        progress = progress,
        nextEpisodeKey = nextEpisodeId?.let { "online:$providerId:$releaseId:$it" },
    )
}

fun buildLocalEpisodePlaybackPlan(
    episode: PlaybackEpisodeRow,
    nextEpisodeId: Long?,
): EpisodePlaybackPlan {
    val episodeKey = "local:${episode.id}"
    return EpisodePlaybackPlan(
        episodeKey = episodeKey,
        titleKey = "local-title:${episode.titleId}",
        title = episode.titleName,
        episodeTitle = episode.fileName,
        ordinal = episode.episodeNumber,
        variants = listOf(
            PlaybackVariant(
                key = "local:${episode.fileUri}",
                episodeKey = episodeKey,
                uri = episode.fileUri,
                kind = PlaybackVariantKind.LOCAL,
                sourceName = "Локальная библиотека",
                localEpisodeId = episode.id,
            ),
        ),
        progress = PlaybackProgressSnapshot(
            positionMs = if (episode.isCompleted) 0L else episode.positionMs,
            durationMs = episode.durationMs ?: 0L,
            isCompleted = episode.isCompleted,
            lastWatchedAt = episode.lastWatchedAt ?: 0L,
        ),
        nextEpisodeKey = nextEpisodeId?.let { "local:$it" },
    )
}
