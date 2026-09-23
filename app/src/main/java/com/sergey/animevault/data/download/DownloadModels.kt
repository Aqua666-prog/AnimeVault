package com.sergey.animevault.data.download

import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Persistent user-facing state for one offline media download. */
data class DownloadEntry(
    val id: String,
    val providerId: String,
    val providerName: String,
    val releaseId: String,
    val releaseName: String,
    val episodeId: String,
    val episodeOrdinal: Double?,
    val episodeName: String?,
    val quality: Int?,
    val translation: String?,
    val translationKey: String?,
    val sourceName: String?,
    val streamType: OnlineStreamType,
    /** Actual provider used for the current stream. For unified downloads this differs from providerId. */
    val streamProviderId: String? = null,
    val streamProviderName: String? = null,
    val sourceHost: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val contentLength: Long = -1L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val operationToken: String? = null,
    val localFilePath: String? = null,
    val localMimeType: String? = null,
    val localEpisodeId: Long? = null,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val attemptCount: Int = 0,
    val failureKind: DownloadFailureKind? = null,
    val diagnosticStage: String? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = status in setOf(
        DownloadStatus.QUEUED,
        DownloadStatus.RESOLVING,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.RETRY_WAIT,
        DownloadStatus.VERIFYING,
    )
    val isPlayableOffline: Boolean get() = status == DownloadStatus.COMPLETED

    val episodeLabel: String
        get() = episodeOrdinal?.let { number ->
            val value = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
            "Серия $value"
        } ?: episodeName?.takeIf(String::isNotBlank) ?: "Серия"
}

internal fun DownloadEntry.belongsToOperation(token: String): Boolean = operationToken == token

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    RETRY_WAIT,
    VERIFYING,
    PAUSED,
    COMPLETED,
    FAILED,
    MISSING,
    REMOVING,
}

enum class DownloadFailureKind {
    TIMEOUT,
    DNS,
    CONNECTION,
    TLS,
    AUTH_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    UNSUPPORTED,
    STORAGE,
    VERIFICATION,
    NETWORK,
    UNKNOWN,
}

data class DownloadMediaSource(
    val url: String,
    val headers: Map<String, String>,
    val providerId: String? = null,
    val providerName: String? = null,
    val streamType: OnlineStreamType? = null,
    val quality: Int? = null,
    val translation: String? = null,
    val translationKey: String? = null,
    val sourceName: String? = null,
    val expiresAtEpochMs: Long? = null,
    val refreshable: Boolean = true,
) {
    val host: String? get() = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
}

internal fun OnlineStream.toDownloadMediaSource(): DownloadMediaSource = DownloadMediaSource(
    url = url,
    headers = headers,
    providerId = providerId,
    providerName = providerName,
    streamType = type,
    quality = quality,
    translation = translation,
    translationKey = translationPreferenceKey,
    sourceName = sourceName,
    expiresAtEpochMs = expiresAtEpochMs,
    refreshable = refreshable,
)

fun OnlineStream.isDownloadable(): Boolean = type == OnlineStreamType.HLS || type == OnlineStreamType.MP4

/** Picks an already resolved native stream while preserving the user's voice/quality preferences. */
fun chooseDownloadStream(
    streams: List<OnlineStream>,
    preferredTranslationKey: String?,
    preferredQuality: Int?,
    allowQualityFallback: Boolean = true,
): OnlineStream? {
    var candidates = streams.filter(OnlineStream::isDownloadable)
    if (!allowQualityFallback && preferredQuality != null) {
        // A master HLS can advertise its renditions only after the downloader opens it, so an
        // unknown-quality HLS stream is still compatible with strict quality mode.
        candidates = candidates.filter {
            it.quality == preferredQuality || (it.quality == null && it.type == OnlineStreamType.HLS)
        }
    }
    if (candidates.isEmpty()) return null
    return candidates.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<OnlineStream>> { indexed ->
                val stream = indexed.value
                buildDownloadScore(stream, preferredTranslationKey, preferredQuality)
            }.thenBy { it.index },
        )
        .first()
        .value
}

private fun buildDownloadScore(
    stream: OnlineStream,
    preferredTranslationKey: String?,
    preferredQuality: Int?,
): Int {
    var score = 0
    if (!preferredTranslationKey.isNullOrBlank() && stream.translationPreferenceKey == preferredTranslationKey) {
        score += 100_000
    }
    if (preferredQuality != null && stream.quality == preferredQuality) score += 20_000
    score += (stream.quality ?: 0).coerceAtMost(2160) * 5
    if (stream.type == OnlineStreamType.MP4) score += 100
    return score
}

fun downloadId(
    providerId: String,
    releaseId: String,
    episodeId: String,
    stream: OnlineStream,
): String {
    // A download represents a logical episode. Selecting another quality or
    // translation replaces its file instead of creating a duplicate episode.
    @Suppress("UNUSED_VARIABLE")
    val selectedVariant = stream
    val raw = listOf(providerId, releaseId, episodeId).joinToString("\u001F")
    return sha256Hex(raw, bytes = 12)
}

/**
 * Stable per-download cache key for signed URLs.
 *
 * Providers frequently rotate a CDN host and authentication query values while the underlying
 * manifest/segment stays the same. Keeping the path and non-authentication query parameters lets
 * Media3 resume the existing cache without collapsing genuinely different query-addressed
 * segments into one resource.
 */
internal fun downloadCacheKey(downloadId: String, rawUrl: String): String {
    val resource = runCatching { URI(rawUrl) }
        .map { uri ->
            val authority = uri.host
                ?.lowercase(Locale.ROOT)
                ?.let { host -> if (uri.port >= 0) "$host:${uri.port}" else host }
                .orEmpty()
            val path = uri.rawPath?.takeIf(String::isNotBlank)
                ?: uri.rawSchemeSpecificPart?.substringBefore('?')
                ?: rawUrl.substringBefore('?').substringBefore('#')
            val stableQuery = uri.rawQuery
                ?.split('&')
                ?.filter(String::isNotBlank)
                ?.filterNot(::isVolatileAuthParameter)
                ?.joinToString("&")
                .orEmpty()
            val location = "$authority$path"
            if (stableQuery.isBlank()) location else "$location?$stableQuery"
        }
        .getOrElse { rawUrl.substringBefore('#') }
    return "animevault-download:$downloadId:${sha256Hex(resource, bytes = 16)}"
}

private fun isVolatileAuthParameter(parameter: String): Boolean {
    val name = parameter.substringBefore('=').trim().lowercase(Locale.ROOT)
    return name in VOLATILE_AUTH_PARAMETERS ||
        name.startsWith("x-amz-") ||
        name.startsWith("x-goog-")
}

private fun sha256Hex(value: String, bytes: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private val VOLATILE_AUTH_PARAMETERS = setOf(
    "auth",
    "authorization",
    "expires",
    "expiry",
    "hdnea",
    "hdnts",
    "hmac",
    "jwt",
    "key-pair-id",
    "policy",
    "sig",
    "signature",
    "token",
)
