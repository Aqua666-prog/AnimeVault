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
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val contentLength: Long = -1L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val operationToken: String? = null,
    val localFilePath: String? = null,
    val localMimeType: String? = null,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val diagnosticStage: String? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
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
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    REMOVING,
}

data class DownloadMediaSource(
    val url: String,
    val headers: Map<String, String>,
)

fun OnlineStream.isDownloadable(): Boolean = type == OnlineStreamType.HLS || type == OnlineStreamType.MP4

/** Picks an already resolved native stream while preserving the user's voice/quality preferences. */
fun chooseDownloadStream(
    streams: List<OnlineStream>,
    preferredTranslationKey: String?,
    preferredQuality: Int?,
): OnlineStream? {
    val candidates = streams.filter(OnlineStream::isDownloadable)
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
    val raw = listOf(
        providerId,
        releaseId,
        episodeId,
        stream.type.name,
        stream.quality?.toString().orEmpty(),
        stream.translation.orEmpty(),
        stream.sourceName.orEmpty(),
    ).joinToString("\u001F")
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
