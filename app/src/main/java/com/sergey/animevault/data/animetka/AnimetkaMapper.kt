package com.sergey.animevault.data.animetka

import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineEpisodeSkipData
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSkipRange
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.OnlineTranslationOption
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.providerTranslationKey
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

internal fun AnimetkaAnimeDto.toOnlineCard(): OnlineReleaseCard = OnlineReleaseCard(
    providerId = OnlineProviderIds.ANIMETKA,
    providerName = ANIMETKA_PROVIDER_NAME,
    id = id,
    alias = id,
    name = animeTitle ?: title,
    englishName = titleEn,
    posterUrl = posterUrl?.toAnimetkaAbsoluteUrl(),
    year = year,
    type = type,
    season = season,
    episodeCount = episodeCount,
    isOngoing = status.orEmpty().contains("ongo", ignoreCase = true) ||
        status.orEmpty().contains("онго", ignoreCase = true),
    genres = genres,
    externalIds = externalIds,
)

internal fun AnimetkaAnimeDto.toOnlineDetails(
    playlist: AnimetkaPlaylistDto,
    selectedTranslation: AnimetkaTranslationDto?,
    translations: List<AnimetkaTranslationDto> = playlist.translations,
): OnlineReleaseDetails {
    val translationId = selectedTranslation?.id ?: playlist.selectedTranslationId
    val translationName = selectedTranslation?.name
        ?: translations.firstOrNull { it.id == translationId }?.name
    val episodes = playlist.episodes.mapIndexed { index, episode ->
        episode.toOnlineEpisode(
            releaseId = id,
            translationId = translationId,
            translationName = translationName,
            index = index,
        )
    }
    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.ANIMETKA,
        providerName = ANIMETKA_PROVIDER_NAME,
        id = id,
        alias = id,
        name = animeTitle ?: title,
        englishName = titleEn,
        posterUrl = posterUrl?.toAnimetkaAbsoluteUrl(),
        year = year,
        type = type,
        season = season,
        episodeCount = episodeCount ?: episodes.size.takeIf { it > 0 },
        description = description,
        notification = null,
        genres = genres,
        isOngoing = status.orEmpty().contains("ongo", ignoreCase = true) ||
            status.orEmpty().contains("онго", ignoreCase = true),
        isBlocked = false,
        episodes = episodes,
        externalIds = externalIds,
        availableTranslations = translations.map { translation ->
            OnlineTranslationOption(
                key = providerTranslationKey(OnlineProviderIds.ANIMETKA, id, translation.id),
                name = translation.name,
                kind = if (translation.subtitles) "Субтитры" else "Озвучка",
                quality = translation.quality,
                episodeCount = translation.episodeCount ?: 0,
            )
        }.sortedWith(
            compareBy<OnlineTranslationOption> { if (it.isSubtitles) 1 else 0 }
                .thenByDescending(OnlineTranslationOption::episodeCount)
                .thenByDescending { it.quality ?: 0 }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        ),
    )
}

private fun AnimetkaEpisodeDto.toOnlineEpisode(
    releaseId: String,
    translationId: String?,
    translationName: String?,
    index: Int,
): OnlineEpisode {
    val ordinal = episodeOrdinal(id) ?: title?.let(::episodeOrdinal) ?: (index + 1).toDouble()
    val translationKey = translationId?.let {
        providerTranslationKey(OnlineProviderIds.ANIMETKA, releaseId, it)
    }
    val episodeSkipData = skipData.toOnlineSkipData()
    val streams = parseAnimetkaPlayerJsStreams(
        raw = file,
        releaseId = releaseId,
        episodeId = id,
        translationId = translationId,
        translationName = translationName,
        translationKey = translationKey,
    ).map { stream -> stream.copy(skipData = episodeSkipData) }
    return OnlineEpisode(
        providerId = OnlineProviderIds.ANIMETKA,
        id = stableAnimetkaEpisodeId(releaseId, id),
        releaseId = releaseId,
        ordinal = ordinal,
        name = title?.takeIf(String::isNotBlank) ?: ordinal?.let { "${displayNumber(it)} серия" },
        previewUrl = previewUrl?.toAnimetkaAbsoluteUrl(),
        durationMs = durationSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(1_000.0)
            ?.roundToLong()
            ?: 0L,
        sortOrder = ordinal ?: (index + 1).toDouble(),
        streams = streams,
        sourceRef = translationId?.let { encodeAnimetkaEpisodeRef(it, id) },
        skipData = episodeSkipData,
    )
}

internal fun parseAnimetkaPlayerJsStreams(
    raw: String?,
    releaseId: String,
    episodeId: String,
    translationId: String?,
    translationName: String?,
    translationKey: String?,
): List<OnlineStream> {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
    val matches = QUALITY_MARKER.findAll(value).toList()
    val variants = if (matches.isEmpty()) {
        listOf(null to value.trim().trim(','))
    } else {
        matches.mapIndexed { index, match ->
            val quality = match.groupValues[1].toIntOrNull()
            val start = match.range.last + 1
            val endExclusive = matches.getOrNull(index + 1)?.range?.first ?: value.length
            val url = value.substring(start, endExclusive).trim().trim(',').trim()
            quality to url
        }
    }
    return variants.mapNotNull { (quality, rawUrl) ->
        val url = rawUrl.takeIf(String::isNotBlank)?.toAnimetkaAbsoluteUrl() ?: return@mapNotNull null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
        val family = sourceFamily(url)
        val type = when {
            url.substringBefore('?').endsWith(".mp4", ignoreCase = true) -> OnlineStreamType.MP4
            else -> OnlineStreamType.HLS
        }
        OnlineStream(
            id = stableStreamId(releaseId, episodeId, translationId, quality, family),
            quality = quality,
            url = url,
            type = type,
            headers = mapOf(
                "Referer" to ANIMETKA_REFERER,
                "Origin" to ANIMETKA_ORIGIN,
            ),
            translation = translationName,
            sourceName = family,
            translationId = translationId,
            providerId = OnlineProviderIds.ANIMETKA,
            providerName = ANIMETKA_PROVIDER_NAME,
            hostFamily = family.lowercase(Locale.ROOT),
            refreshable = true,
            translationKey = translationKey,
        )
    }.distinctBy { stream ->
        listOf(stream.quality?.toString().orEmpty(), stream.hostFamily.orEmpty(), stableUrlIdentity(stream.url))
            .joinToString("\u001F")
    }
}

internal fun stableAnimetkaEpisodeId(releaseId: String, rawEpisodeId: String): String =
    "animetka:$releaseId:${rawEpisodeId.trim()}"

internal fun encodeAnimetkaEpisodeRef(translationId: String, rawEpisodeId: String): String =
    translationId.trim() + EPISODE_REF_SEPARATOR + rawEpisodeId.trim()

internal fun decodeAnimetkaEpisodeRef(value: String?): Pair<String, String>? {
    val raw = value?.takeIf(String::isNotBlank) ?: return null
    val separator = raw.indexOf(EPISODE_REF_SEPARATOR)
    if (separator <= 0 || separator >= raw.lastIndex) return null
    return raw.substring(0, separator) to raw.substring(separator + EPISODE_REF_SEPARATOR.length)
}

internal fun String.toAnimetkaAbsoluteUrl(): String {
    val raw = trim()
    if (raw.isBlank()) return raw
    return when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> ANIMETKA_BASE_URL.trimEnd('/') + raw
        else -> ANIMETKA_BASE_URL + raw
    }
}

internal fun AnimetkaSkipDataDto?.toOnlineSkipData(): OnlineEpisodeSkipData? {
    this ?: return null
    val openingRange = opening.toOnlineSkipRange()
    val endingRange = ending.toOnlineSkipRange()
    if (openingRange == null && endingRange == null) return null
    return OnlineEpisodeSkipData(opening = openingRange, ending = endingRange)
}

private fun AnimetkaSkipRangeDto?.toOnlineSkipRange(): OnlineSkipRange? {
    this ?: return null
    if (!startSeconds.isFinite() || !stopSeconds.isFinite() || startSeconds < 0.0 || stopSeconds <= startSeconds) {
        return null
    }
    val start = (startSeconds * 1_000.0).roundToLong()
    val stop = (stopSeconds * 1_000.0).roundToLong()
    if (start < 0L || stop <= start) return null
    return OnlineSkipRange(startMs = start, endMs = stop)
}

private fun stableStreamId(
    releaseId: String,
    episodeId: String,
    translationId: String?,
    quality: Int?,
    family: String,
): String = listOf(
    "animetka",
    releaseId,
    episodeId,
    translationId.orEmpty(),
    quality?.toString().orEmpty(),
    family.lowercase(Locale.ROOT),
).joinToString(":")

private fun sourceFamily(url: String): String {
    val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
    return when {
        "kodik" in host -> "Kodik"
        "libria" in host -> "AniLibria"
        host.isNotBlank() -> host
        else -> "Аниметка"
    }
}

private fun stableUrlIdentity(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return sha256(url.substringBefore('#'))
    val query = uri.rawQuery
        ?.split('&')
        ?.filter(String::isNotBlank)
        ?.filterNot { pair ->
            val name = pair.substringBefore('=').lowercase(Locale.ROOT)
            name in VOLATILE_QUERY_NAMES || name.startsWith("x-amz-") || name.startsWith("x-goog-")
        }
        ?.joinToString("&")
        .orEmpty()
    val stable = buildString {
        append(uri.host.orEmpty().lowercase(Locale.ROOT))
        append(uri.rawPath.orEmpty())
        if (query.isNotBlank()) append('?').append(query)
    }
    return sha256(stable)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(8)
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun episodeOrdinal(value: String): Double? {
    val trimmed = value.trim()
    EPISODE_ID_NUMBER.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
    return FIRST_NUMBER.find(trimmed)?.value?.replace(',', '.')?.toDoubleOrNull()
}

private fun displayNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private val QUALITY_MARKER = Regex("\\[(\\d{3,4})]")
private val EPISODE_ID_NUMBER = Regex("[sSeE]?(\\d+(?:[.,]\\d+)?)")
private val FIRST_NUMBER = Regex("\\d+(?:[.,]\\d+)?")
private const val EPISODE_REF_SEPARATOR = "\u001E"
private val VOLATILE_QUERY_NAMES = setOf(
    "token", "sig", "signature", "expires", "expire", "exp", "auth", "hash", "key", "md5", "st", "e",
)
