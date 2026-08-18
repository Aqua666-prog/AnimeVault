package com.sergey.animevault.data.playback

import com.sergey.animevault.data.model.PlaybackEpisodeRow
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import java.util.Locale

/**
 * A provider-neutral way to obtain one episode.
 *
 * The player should choose between variants instead of knowing whether the bytes come from
 * a local file, AniLiberty, Kodik, another provider, or an embedded web player.
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

/** Generic ranking used by both offline/online and future TV/background players. */
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
        val preferredTranslation = preference.translation.normalized()
        val preferredSource = preference.sourceName.normalized()
        val preferredProvider = preference.providerId.normalized()
        return variants.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<PlaybackVariant>> { indexed ->
                    preferenceScore(
                        indexed.value,
                        preferredTranslation = preferredTranslation,
                        preferredQuality = preference.quality,
                        preferredSource = preferredSource,
                        preferredProvider = preferredProvider,
                        preferLocal = preference.preferLocal,
                    )
                }.thenBy { it.index },
            )
            .map(IndexedValue<PlaybackVariant>::value)
    }

    fun selectFallback(
        variants: List<PlaybackVariant>,
        current: PlaybackVariant,
        failedVariantKeys: Set<String>,
        failure: PlaybackFailure? = null,
    ): PlaybackVariant? {
        if (failure != null && !PlaybackFallbackPolicy.shouldTryAlternative(failure.kind)) return null
        val currentTranslation = current.translation.normalized()
        val currentSource = current.sourceName.normalized()
        val currentProvider = current.providerId.normalized()
        val currentQuality = current.quality
        return variants
            .asSequence()
            .filter { it.uri.isNotBlank() }
            .filter { it.key !in failedVariantKeys }
            .filterNot { it.key == current.key }
            .sortedWith(
                compareByDescending<PlaybackVariant> { it.isLocal }
                    .thenByDescending {
                        currentTranslation != null && it.translation.normalized() == currentTranslation
                    }
                    .thenByDescending {
                        currentProvider != null && it.providerId.normalized() == currentProvider
                    }
                    .thenByDescending {
                        currentSource != null && it.sourceName.normalized() == currentSource
                    }
                    .thenBy { qualityDistance(it.quality, currentQuality) }
                    .thenByDescending(PlaybackVariant::isNativePlayable)
                    .thenByDescending { it.quality ?: 0 }
                    .thenBy(PlaybackVariant::displayName),
            )
            .firstOrNull()
    }

    private fun preferenceScore(
        variant: PlaybackVariant,
        preferredTranslation: String?,
        preferredQuality: Int?,
        preferredSource: String?,
        preferredProvider: String?,
        preferLocal: Boolean,
    ): Int {
        var score = 0
        if (preferLocal && variant.isLocal) score += 100_000
        if (preferredTranslation != null && variant.translation.normalized() == preferredTranslation) score += 10_000
        if (preferredQuality != null && variant.quality == preferredQuality) score += 1_500
        if (preferredProvider != null && variant.providerId.normalized() == preferredProvider) score += 900
        if (preferredSource != null && variant.sourceName.normalized() == preferredSource) score += 700
        if (variant.isNativePlayable) score += 120
        score += (variant.quality ?: 0).coerceAtMost(2160) / 10
        return score
    }

    private fun qualityDistance(candidate: Int?, target: Int?): Int = when {
        candidate == null && target == null -> 0
        candidate == null || target == null -> Int.MAX_VALUE / 2
        else -> kotlin.math.abs(candidate - target)
    }

    private fun String?.normalized(): String? = this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.lowercase(Locale.ROOT)
}

object PlaybackFallbackPolicy {
    /** Authentication/configuration failures should be surfaced instead of silently hiding them. */
    fun shouldTryAlternative(kind: PlaybackFailureKind): Boolean = when (kind) {
        PlaybackFailureKind.AUTH_REQUIRED -> false
        else -> true
    }
}

fun OnlineStream.toPlaybackVariant(
    episodeKey: String,
    providerId: String,
    providerName: String,
): PlaybackVariant = PlaybackVariant(
    key = OnlineStreamVariantKeys.keyOf(this),
    episodeKey = episodeKey,
    uri = url,
    kind = when (type) {
        OnlineStreamType.HLS -> PlaybackVariantKind.HLS
        OnlineStreamType.MP4 -> PlaybackVariantKind.MP4
        OnlineStreamType.EMBED -> PlaybackVariantKind.EMBED
    },
    providerId = providerId,
    providerName = providerName,
    sourceName = sourceName,
    translation = translation,
    quality = quality,
    headers = headers,
)

object OnlineStreamVariantKeys {
    fun keyOf(stream: OnlineStream): String = "${stream.type}\u001F${stream.url}"
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
