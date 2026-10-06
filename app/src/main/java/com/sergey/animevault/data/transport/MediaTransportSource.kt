package com.sergey.animevault.data.transport

import java.net.URI
import java.util.Locale

/**
 * Provider-neutral description of a concrete media route.
 *
 * Playback and downloads intentionally share this model. A provider can rotate a signed URL while
 * the logical source (voice/quality/CDN) stays the same, so [refreshIdentity] and [routeFamily]
 * are kept separately from [uri].
 */
data class MediaTransportSource(
    val key: String,
    val uri: String,
    val kind: TransportKind,
    val providerId: String? = null,
    val providerName: String? = null,
    val sourceName: String? = null,
    val translation: String? = null,
    val translationKey: String? = null,
    val quality: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val offlineCacheId: String? = null,
    val routeFamily: String? = null,
    val refreshable: Boolean = false,
    val expiresAtEpochMs: Long? = null,
    val refreshIdentity: String? = null,
) {
    val isLocal: Boolean get() = kind == TransportKind.LOCAL
    val isNativePlayable: Boolean get() = kind != TransportKind.EMBED
    val isDownloadable: Boolean get() = kind == TransportKind.HLS || kind == TransportKind.MP4

    val normalizedRouteFamily: String?
        get() = normalizeRouteFamily(routeFamily) ?: inferRouteFamily(uri)

    val routeKey: String?
        get() = normalizedRouteFamily?.let { route ->
            "${providerId.orEmpty().trim().lowercase(Locale.ROOT)}\u001F$route"
        }

    /** Stable logical identity that survives signed-query/host-token rotation. */
    val stableRefreshIdentity: String
        get() = refreshIdentity?.trim()?.takeIf(String::isNotBlank)
            ?: listOf(
                providerId.orEmpty(),
                translationKey.orEmpty(),
                quality?.toString().orEmpty(),
                normalizedRouteFamily.orEmpty(),
                kind.name,
            ).joinToString("\u001F")

    fun expiresSoon(nowMs: Long, graceMs: Long = DEFAULT_REFRESH_GRACE_MS): Boolean =
        expiresAtEpochMs?.let { it <= nowMs + graceMs } == true

    companion object {
        const val DEFAULT_REFRESH_GRACE_MS: Long = 60_000L
    }
}

enum class TransportKind {
    LOCAL,
    HLS,
    MP4,
    EMBED,
    EXTERNAL,
}

enum class TransportFailureKind {
    TIMEOUT,
    DNS,
    CONNECTION,
    TLS,
    AUTH_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    DECODER,
    UNSUPPORTED,
    STORAGE,
    VERIFICATION,
    NETWORK,
    UNKNOWN,
}

/** Cross-feature rules shared by streaming and offline downloads. */
object TransportFailurePolicy {
    fun isRouteSensitive(kind: TransportFailureKind): Boolean = kind in ROUTE_SENSITIVE

    fun shouldRefresh(kind: TransportFailureKind): Boolean = kind in REFRESHABLE_FAILURES

    fun shouldRefresh(
        source: MediaTransportSource,
        kind: TransportFailureKind,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!source.refreshable) return false
        if (shouldRefresh(kind)) return true
        return source.expiresSoon(nowMs) && kind in EXPIRY_TRANSIENT_FAILURES
    }

    private val ROUTE_SENSITIVE = setOf(
        TransportFailureKind.TIMEOUT,
        TransportFailureKind.DNS,
        TransportFailureKind.CONNECTION,
        TransportFailureKind.TLS,
        TransportFailureKind.FORBIDDEN,
        TransportFailureKind.NOT_FOUND,
        TransportFailureKind.RATE_LIMITED,
        TransportFailureKind.SERVER,
        TransportFailureKind.NETWORK,
    )

    private val REFRESHABLE_FAILURES = setOf(
        TransportFailureKind.AUTH_REQUIRED,
        TransportFailureKind.FORBIDDEN,
        TransportFailureKind.NOT_FOUND,
    )

    private val EXPIRY_TRANSIENT_FAILURES = setOf(
        TransportFailureKind.TIMEOUT,
        TransportFailureKind.CONNECTION,
        TransportFailureKind.SERVER,
        TransportFailureKind.NETWORK,
    )
}

data class TransportPreference(
    val translation: String? = null,
    val translationKey: String? = null,
    val quality: Int? = null,
    val sourceName: String? = null,
    val providerId: String? = null,
    val preferLocal: Boolean = true,
    val allowHigherQuality: Boolean = true,
)

/**
 * Shared ranking/fallback planner. It deliberately contains no Android/Media3/WorkManager code.
 */
object TransportCandidatePlanner {
    fun orderPreferred(
        sources: List<MediaTransportSource>,
        preference: TransportPreference = TransportPreference(),
        additionalScore: (MediaTransportSource) -> Int = { 0 },
    ): List<MediaTransportSource> {
        val preferredTranslation = preference.translation.normalized()
        val preferredTranslationKey = preference.translationKey?.trim()?.takeIf(String::isNotBlank)
        val preferredSource = preference.sourceName.normalized()
        val preferredProvider = preference.providerId.normalized()
        return sources.withIndex()
            .filter { indexed ->
                val candidate = indexed.value
                preference.allowHigherQuality || preference.quality == null || candidate.quality == null ||
                    candidate.quality <= preference.quality
            }
            .sortedWith(
                compareByDescending<IndexedValue<MediaTransportSource>> { indexed ->
                    preferenceScore(
                        source = indexed.value,
                        preferredTranslation = preferredTranslation,
                        preferredTranslationKey = preferredTranslationKey,
                        preferredQuality = preference.quality,
                        preferredSource = preferredSource,
                        preferredProvider = preferredProvider,
                        preferLocal = preference.preferLocal,
                    ) + additionalScore(indexed.value)
                }.thenBy { it.index },
            )
            .map(IndexedValue<MediaTransportSource>::value)
    }

    fun selectFallback(
        sources: List<MediaTransportSource>,
        current: MediaTransportSource,
        failedKeys: Set<String>,
        blockedRouteFamilies: Set<String> = emptySet(),
        failureKind: TransportFailureKind? = null,
    ): MediaTransportSource? {
        val currentTranslation = current.translation.normalized()
        val currentTranslationKey = current.translationKey?.trim()?.takeIf(String::isNotBlank)
        val currentSource = current.sourceName.normalized()
        val currentProvider = current.providerId.normalized()
        val currentRoute = current.normalizedRouteFamily
        val currentQuality = current.quality
        val blockedRoutes = blockedRouteFamilies.mapNotNull(::normalizeRouteFamily).toSet()
        val avoidCurrentRoute = failureKind?.let(TransportFailurePolicy::isRouteSensitive) == true && currentRoute != null

        return sources.asSequence()
            .filter { it.uri.isNotBlank() }
            .filter { it.key !in failedKeys }
            .filterNot { it.key == current.key }
            .filter { candidate ->
                val route = candidate.normalizedRouteFamily
                route == null || route !in blockedRoutes
            }
            .sortedWith(
                compareByDescending<MediaTransportSource> { it.isLocal }
                    .thenByDescending {
                        currentTranslationKey != null && it.translationKey == currentTranslationKey
                    }
                    .thenByDescending {
                        currentTranslation != null && it.translation.normalized() == currentTranslation
                    }
                    .thenByDescending {
                        currentProvider != null && it.providerId.normalized() == currentProvider
                    }
                    .thenByDescending {
                        avoidCurrentRoute && it.normalizedRouteFamily != null &&
                            it.normalizedRouteFamily != currentRoute
                    }
                    .thenByDescending { it.quality != null && it.quality == currentQuality }
                    .thenBy { qualityDistance(it.quality, currentQuality) }
                    .thenByDescending {
                        currentSource != null && it.sourceName.normalized() == currentSource
                    }
                    .thenByDescending(MediaTransportSource::isNativePlayable)
                    .thenByDescending { it.quality ?: 0 }
                    .thenBy { it.sourceName.orEmpty() }
                    .thenBy { it.providerName.orEmpty() },
            )
            .firstOrNull()
    }

    private fun preferenceScore(
        source: MediaTransportSource,
        preferredTranslation: String?,
        preferredTranslationKey: String?,
        preferredQuality: Int?,
        preferredSource: String?,
        preferredProvider: String?,
        preferLocal: Boolean,
    ): Int {
        var score = 0
        if (preferLocal && source.isLocal) score += 100_000
        if (preferredTranslationKey != null && source.translationKey == preferredTranslationKey) score += 40_000
        if (preferredTranslation != null && source.translation.normalized() == preferredTranslation) score += 10_000
        if (preferredQuality != null && source.quality == preferredQuality) score += 1_500
        if (preferredProvider != null && source.providerId.normalized() == preferredProvider) score += 900
        if (preferredSource != null && source.sourceName.normalized() == preferredSource) score += 700
        if (source.isNativePlayable) score += 120
        score += (source.quality ?: 0).coerceAtMost(2160) / 10
        return score
    }

    private fun qualityDistance(candidate: Int?, target: Int?): Int = when {
        candidate == null && target == null -> 0
        candidate == null || target == null -> Int.MAX_VALUE / 2
        else -> kotlin.math.abs(candidate - target)
    }
}

fun inferRouteFamily(rawUri: String): String? = runCatching {
    URI(rawUri).host
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
}.getOrNull()

fun normalizeRouteFamily(value: String?): String? = value
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.lowercase(Locale.ROOT)

private fun String?.normalized(): String? = this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.lowercase(Locale.ROOT)

/** Shared refresh reconciliation for player and downloader. */
object TransportRefreshResolver {
    fun selectReplacement(
        current: MediaTransportSource,
        fresh: List<MediaTransportSource>,
    ): MediaTransportSource? {
        if (fresh.isEmpty()) return null
        return fresh.firstOrNull { it.stableRefreshIdentity == current.stableRefreshIdentity }
            ?: fresh.firstOrNull {
                current.translationKey != null && it.translationKey == current.translationKey &&
                    it.quality == current.quality &&
                    providersMatch(current, it)
            }
            ?: fresh.firstOrNull {
                current.translation != null && it.translation == current.translation &&
                    it.quality == current.quality &&
                    providersMatch(current, it)
            }
            ?: TransportCandidatePlanner.orderPreferred(
                sources = fresh,
                preference = TransportPreference(
                    translation = current.translation,
                    translationKey = current.translationKey,
                    quality = current.quality,
                    sourceName = current.sourceName,
                    providerId = current.providerId,
                    preferLocal = current.isLocal,
                ),
            ).firstOrNull()
    }

    /**
     * Freshly resolved signed URLs supersede an older stored route with the same logical identity.
     * This prevents a downloader from immediately trying the known-stale URL after a successful refresh.
     */
    fun mergeFreshAndStored(
        fresh: List<MediaTransportSource>,
        stored: List<MediaTransportSource>,
    ): List<MediaTransportSource> {
        if (fresh.isEmpty()) return stored.distinctBy(MediaTransportSource::key)
        val refreshedIdentities = fresh.map(MediaTransportSource::stableRefreshIdentity).toSet()
        return buildList {
            addAll(fresh)
            stored.filterTo(this) { it.stableRefreshIdentity !in refreshedIdentities }
        }.distinctBy(MediaTransportSource::key)
    }

    private fun providersMatch(left: MediaTransportSource, right: MediaTransportSource): Boolean =
        left.providerId.isNullOrBlank() || right.providerId.isNullOrBlank() || left.providerId == right.providerId
}
