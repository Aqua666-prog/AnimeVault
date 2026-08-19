package com.sergey.animevault.data.online

import com.sergey.animevault.data.playback.PlaybackFailureKind
import com.sergey.animevault.data.playback.WatchState
object OnlineProviderIds {
    const val KODIK = "kodik"
    const val ANI_LIBERTY = "aniliberty"
    const val ANIME_LIB = "animelib"
    const val ANIME_VOST = "animevost"
    const val JUT_SU = "jutsu"
    const val DREAMERSCAST = "dreamerscast"
    const val ANIMEDIA = "animedia"
    const val ANIME_ON = "animeon"
    const val SAMEBAND = "sameband"
    const val ANIME_BEST = "animebest"
    const val YUMMY = "yummy"
    const val UNIFIED = "all"
}

enum class ProviderAuthMode {
    NONE,
    OPTIONAL_TOKEN,
    REQUIRED_TOKEN,
    ACCOUNT,
}

enum class ProviderSearchMode {
    NONE,
    TEXT,
    URL_OR_SLUG,
}

/**
 * Explicit feature contract for an online adapter. UI and aggregators use this
 * instead of guessing capabilities from provider ids or implementation details.
 */
data class ProviderCapabilities(
    val catalog: Boolean = true,
    val search: Boolean = true,
    val releaseDetails: Boolean = true,
    val episodes: Boolean = true,
    val streams: Boolean = true,
    val translations: Boolean = true,
    val subtitles: Boolean = false,
    val directPlayback: Boolean = true,
    val searchMode: ProviderSearchMode = ProviderSearchMode.TEXT,
) {
    val supportsUnifiedTextSearch: Boolean
        get() = search && searchMode == ProviderSearchMode.TEXT

    fun compactLabel(): String = buildList {
        if (catalog) add("каталог")
        if (search) add(if (searchMode == ProviderSearchMode.URL_OR_SLUG) "ссылка/slug" else "поиск")
        if (episodes) add("серии")
        if (streams) add("потоки")
        if (translations) add("озвучки")
        if (subtitles) add("субтитры")
    }.joinToString(" · ").ifBlank { "без онлайн-функций" }

    companion object {
        fun aggregate(items: Collection<ProviderCapabilities>): ProviderCapabilities {
            if (items.isEmpty()) return ProviderCapabilities(
                catalog = false, search = false, releaseDetails = false, episodes = false,
                streams = false, translations = false, subtitles = false, directPlayback = false,
                searchMode = ProviderSearchMode.NONE,
            )
            return ProviderCapabilities(
                catalog = items.any { it.catalog },
                search = items.any { it.supportsUnifiedTextSearch },
                releaseDetails = items.any { it.releaseDetails },
                episodes = items.any { it.episodes },
                streams = items.any { it.streams },
                translations = items.any { it.translations },
                subtitles = items.any { it.subtitles },
                directPlayback = items.any { it.directPlayback },
                searchMode = if (items.any { it.supportsUnifiedTextSearch }) ProviderSearchMode.TEXT else ProviderSearchMode.NONE,
            )
        }
    }
}

data class OnlineProviderDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val authMode: ProviderAuthMode = ProviderAuthMode.NONE,
    val isExperimental: Boolean = false,
    val searchHint: String = "Найти аниме",
    val healthProbeQuery: String = "",
    val minimumSearchLength: Int = 1,
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
)

data class ExternalAnimeIds(
    val shikimoriId: Long? = null,
    val malId: Long? = null,
    val anilistId: Long? = null,
) {
    val hasAny: Boolean get() = shikimoriId != null || malId != null || anilistId != null
}

data class OnlineCatalogPage(
    val releases: List<OnlineReleaseCard>,
    val currentPage: Int,
    val totalPages: Int,
)

data class OnlineReleaseCard(
    val providerId: String,
    val providerName: String,
    val id: String,
    val alias: String,
    val name: String,
    val englishName: String?,
    val posterUrl: String?,
    val year: Int?,
    val type: String?,
    val season: String?,
    val episodeCount: Int?,
    val isOngoing: Boolean,
    val genres: List<String> = emptyList(),
    val externalIds: ExternalAnimeIds = ExternalAnimeIds(),
)

data class OnlineReleaseDetails(
    val providerId: String,
    val providerName: String,
    val id: String,
    val alias: String,
    val name: String,
    val englishName: String?,
    val posterUrl: String?,
    val year: Int?,
    val type: String?,
    val season: String?,
    val episodeCount: Int?,
    val description: String?,
    val notification: String?,
    val genres: List<String>,
    val isOngoing: Boolean,
    val isBlocked: Boolean,
    val episodes: List<OnlineEpisode>,
    val externalIds: ExternalAnimeIds = ExternalAnimeIds(),
)

data class OnlineEpisodeSource(
    val providerId: String,
    val releaseId: String,
    val episodeId: String,
    val ordinal: Double?,
    val name: String?,
    val previewUrl: String?,
    val durationMs: Long,
    val sortOrder: Double?,
    val streams: List<OnlineStream>,
    val sourceRef: String? = null,
) {
    fun toEpisode(): OnlineEpisode = OnlineEpisode(
        providerId = providerId,
        id = episodeId,
        releaseId = releaseId,
        ordinal = ordinal,
        name = name,
        previewUrl = previewUrl,
        durationMs = durationMs,
        sortOrder = sortOrder,
        streams = streams,
        sourceRef = sourceRef,
    )

    companion object {
        fun fromEpisode(episode: OnlineEpisode): OnlineEpisodeSource = OnlineEpisodeSource(
            providerId = episode.providerId,
            releaseId = episode.releaseId,
            episodeId = episode.id,
            ordinal = episode.ordinal,
            name = episode.name,
            previewUrl = episode.previewUrl,
            durationMs = episode.durationMs,
            sortOrder = episode.sortOrder,
            streams = episode.streams,
            sourceRef = episode.sourceRef,
        )
    }
}

data class OnlineEpisode(
    val providerId: String,
    val id: String,
    val releaseId: String,
    val ordinal: Double?,
    val name: String?,
    val previewUrl: String?,
    val durationMs: Long,
    val sortOrder: Double?,
    val streams: List<OnlineStream>,
    val sourceRef: String? = null,
    val sources: List<OnlineEpisodeSource> = emptyList(),
) {
    val hasStream: Boolean
        get() = streams.isNotEmpty() || !sourceRef.isNullOrBlank() || sources.any {
            it.streams.isNotEmpty() || !it.sourceRef.isNullOrBlank()
        }
}

enum class OnlineStreamType {
    HLS,
    MP4,
    EMBED,
}

data class OnlineStream(
    val id: String,
    val quality: Int?,
    val url: String,
    val type: OnlineStreamType,
    val headers: Map<String, String> = emptyMap(),
    val translation: String? = null,
    val sourceName: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
) {
    val translationPreferenceKey: String?
        get() = translation
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { translationName ->
                translationName + TRANSLATION_KEY_SEPARATOR + sourceName.orEmpty().trim()
            }

    val displayName: String
        get() = listOfNotNull(
            quality?.let { "${it}p" },
            translation?.takeIf(String::isNotBlank),
            sourceName?.takeIf(String::isNotBlank),
        ).distinct().joinToString(" · ").ifBlank {
            if (type == OnlineStreamType.EMBED) "Веб-плеер" else "Авто"
        }
}

data class OnlineTranslationOption(
    val key: String,
    val name: String,
    val kind: String?,
) {
    val displayName: String
        get() = listOfNotNull(name, kind?.takeIf(String::isNotBlank))
            .distinct()
            .joinToString(" · ")
}

internal fun OnlineReleaseDetails.translationOptions(): List<OnlineTranslationOption> = episodes
    .asSequence()
    .flatMap { it.streams.asSequence() }
    .mapNotNull { stream ->
        val key = stream.translationPreferenceKey ?: return@mapNotNull null
        OnlineTranslationOption(
            key = key,
            name = stream.translation.orEmpty().trim(),
            kind = stream.sourceName?.trim()?.takeIf(String::isNotBlank),
        )
    }
    .distinctBy(OnlineTranslationOption::key)
    .toList()

internal fun List<OnlineStream>.prioritizeTranslation(preferredKey: String?): List<OnlineStream> {
    if (preferredKey.isNullOrBlank()) return this
    val (preferred, fallback) = partition { it.translationPreferenceKey == preferredKey }
    return if (preferred.isEmpty()) this else preferred + fallback
}

/**
 * Keeps the user's per-title voice choice more important than quality, then
 * restores the last selected quality inside that voice when it is available.
 * Kotlin's sortedWith is stable, so equal-score streams keep provider order.
 */
internal fun List<OnlineStream>.prioritizePlaybackPreferences(
    preferredTranslationKey: String?,
    preferredQuality: Int?,
): List<OnlineStream> {
    if (preferredTranslationKey.isNullOrBlank() && preferredQuality == null) return this
    if (none { stream ->
            (!preferredTranslationKey.isNullOrBlank() && stream.translationPreferenceKey == preferredTranslationKey) ||
                (preferredQuality != null && stream.quality == preferredQuality)
        }
    ) {
        return this
    }
    return withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<OnlineStream>> { indexed ->
                if (!preferredTranslationKey.isNullOrBlank() &&
                    indexed.value.translationPreferenceKey == preferredTranslationKey
                ) 2 else 0
            }.thenByDescending { indexed ->
                if (preferredQuality != null && indexed.value.quality == preferredQuality) 1 else 0
            }.thenBy { it.index },
        )
        .map(IndexedValue<OnlineStream>::value)
}

data class OnlineWatchProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isCompleted: Boolean = false,
    val lastWatchedAt: Long = 0,
    val firstPlayedAt: Long = 0,
    val completedAt: Long? = null,
    val playCount: Int = 0,
) {
    val watchState: WatchState
        get() = when {
            isCompleted -> WatchState.COMPLETED
            positionMs > 0L -> WatchState.IN_PROGRESS
            else -> WatchState.NOT_STARTED
        }

    val fraction: Float
        get() = when {
            isCompleted -> 1f
            durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

data class ProviderAccountState(
    val providerId: String,
    val isSignedIn: Boolean,
    val displayName: String? = null,
)

enum class ProviderHealthStatus {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    DEGRADED,
    NEEDS_CONFIGURATION,
    UNAVAILABLE,
}

data class ProviderHealthState(
    val providerId: String,
    val status: ProviderHealthStatus = ProviderHealthStatus.UNKNOWN,
    val latencyMs: Long? = null,
    val message: String? = null,
    val checkedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val lastOperation: ProviderOperation? = null,
    val lastFailureKind: PlaybackFailureKind? = null,
    val cooldownUntilMs: Long? = null,
)

data class ProviderLoginResult(
    val displayName: String? = null,
)

private const val TRANSLATION_KEY_SEPARATOR = "\u001F"
