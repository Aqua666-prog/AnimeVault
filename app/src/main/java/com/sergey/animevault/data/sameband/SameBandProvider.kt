package com.sergey.animevault.data.sameband

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProvider
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.absoluteUrl
import com.sergey.animevault.data.online.decodeHtml
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.firstTagText
import com.sergey.animevault.data.online.htmlStartTags
import com.sergey.animevault.data.online.htmlText
import com.sergey.animevault.data.online.metaContent
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.data.online.parseHtmlAttributes
import java.util.Locale
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * SameBand public catalogue and direct HLS adapter.
 *
 * Flow: /novinki or DLE search -> anime page -> public player iframe ->
 * Playerjs playlist JSON -> quality-tagged HLS files.
 */
class SameBandProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val gson: Gson = Gson(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.SAMEBAND,
        name = "SameBand",
        description = "Собственная озвучка SameBand, прямые HLS-потоки до 1080p",
        isExperimental = true,
        searchHint = "Название на русском, от 4 символов",
        minimumSearchLength = 4,
    )

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        if (safePage > 1) return OnlineCatalogPage(emptyList(), safePage, safePage)

        val query = search.trim()
        if (query.isNotBlank() && query.length < MIN_SEARCH_LENGTH) {
            return OnlineCatalogPage(emptyList(), 1, 1)
        }

        val document = if (query.isBlank()) {
            client.executeText(
                Request.Builder()
                    .url("$BASE_URL/novinki")
                    .get()
                    .onlineHeaders(referer = "$BASE_URL/")
                    .build(),
                descriptor.name,
            )
        } else {
            client.executeText(
                Request.Builder()
                    .url("$BASE_URL/index.php?do=search")
                    .post(
                        FormBody.Builder()
                            .add("do", "search")
                            .add("subaction", "search")
                            .add("search_start", "0")
                            .add("full_search", "0")
                            .add("result_from", "1")
                            .add("story", query)
                            .build(),
                    )
                    .onlineHeaders(referer = "$BASE_URL/")
                    .build(),
                descriptor.name,
            )
        }

        val cards = parseSameBandCatalog(document, ongoing = query.isBlank())
            .filter { query.isBlank() || fuzzyContains(it.name, query) }
            .take(limit.coerceAtLeast(1))
            .map(SameBandCatalogItem::toCard)

        return OnlineCatalogPage(cards, 1, 1)
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val releaseUrl = id.absoluteUrl(BASE_URL)
        val document = client.executeText(
            Request.Builder()
                .url(releaseUrl)
                .get()
                .onlineHeaders(referer = "$BASE_URL/")
                .build(),
            descriptor.name,
        )

        val playerUrl = parseSameBandPlayerUrl(document)
            ?.absoluteUrl(BASE_URL)
            ?: throw OnlineSourceException("SameBand: плеер релиза не найден")
        val playerDocument = client.executeText(
            Request.Builder()
                .url(playerUrl)
                .get()
                .onlineHeaders(referer = releaseUrl)
                .build(),
            descriptor.name,
        )
        val playlistUrl = parseSameBandPlaylistUrl(playerDocument)
            ?.absoluteUrl(BASE_URL)
            ?: throw OnlineSourceException("SameBand: плейлист релиза не найден")
        val playlistJson = client.executeText(
            Request.Builder()
                .url(playlistUrl)
                .get()
                .onlineHeaders(referer = playerUrl)
                .build(),
            descriptor.name,
        )
        val episodes = parseSameBandPlaylist(playlistJson, id, gson)
        if (episodes.isEmpty()) throw OnlineSourceException("SameBand не отдал доступных серий")

        return parseSameBandRelease(id, document, episodes)
    }

    private companion object {
        const val BASE_URL = "https://sameband.studio"
        const val MIN_SEARCH_LENGTH = 4
    }
}

internal data class SameBandCatalogItem(
    val path: String,
    val name: String,
    val posterUrl: String?,
    val isOngoing: Boolean,
) {
    fun toCard() = OnlineReleaseCard(
        providerId = OnlineProviderIds.SAMEBAND,
        providerName = "SameBand",
        id = path,
        alias = path.substringAfterLast('/').substringBeforeLast('.'),
        name = name,
        englishName = null,
        posterUrl = posterUrl?.absoluteUrl("https://sameband.studio"),
        year = YEAR_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull(),
        type = null,
        season = null,
        episodeCount = null,
        isOngoing = isOngoing,
        genres = emptyList(),
    )
}

internal fun parseSameBandCatalog(document: String, ongoing: Boolean): List<SameBandCatalogItem> = ANCHOR_TAG_REGEX
    .findAll(document)
    .mapNotNull { anchorMatch ->
        val attributes = parseHtmlAttributes(anchorMatch.value)
        if (!attributes["class"].hasCssClass("image")) return@mapNotNull null
        val href = attributes["href"]?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val windowEnd = (anchorMatch.range.first + CARD_WINDOW_AFTER).coerceAtMost(document.length)
        val windowTags = htmlStartTags(document.substring(anchorMatch.range.first, windowEnd))
        val title = windowTags.firstNotNullOfOrNull { tag ->
            if (tag.attributes["class"].hasCssClass("poster")) tag.attributes["title"] else null
        }?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val poster = windowTags.firstNotNullOfOrNull { tag ->
            if (tag.name == "img" && tag.attributes["class"].hasCssClass("swiper-lazy")) {
                tag.attributes["src"] ?: tag.attributes["data-src"]
            } else null
        }?.trim()?.takeIf(String::isNotBlank)
        SameBandCatalogItem(
            path = href,
            name = title,
            posterUrl = poster,
            isOngoing = ongoing,
        )
    }
    .distinctBy { it.path }
    .toList()

internal fun parseSameBandPlayerUrl(document: String): String? {
    val marker = document.indexOf("player-content", ignoreCase = true)
    val scoped = if (marker >= 0) {
        document.substring(marker, (marker + PLAYER_SCOPE_LENGTH).coerceAtMost(document.length))
    } else {
        document
    }
    return htmlStartTags(scoped)
        .firstOrNull { it.name == "iframe" && !it.attributes["src"].isNullOrBlank() }
        ?.attributes
        ?.get("src")
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

internal fun parseSameBandPlaylistUrl(document: String): String? = SAMEBAND_PLAYLIST_REGEX
    .find(document)
    ?.groupValues
    ?.get(2)
    ?.let(::decodeHtml)
    ?.trim()
    ?.replace(' ', '_')
    ?.takeIf(String::isNotBlank)

internal fun parseSameBandPlaylist(
    payload: String,
    releaseId: String,
    gson: Gson = Gson(),
): List<OnlineEpisode> {
    val root = runCatching { gson.fromJson(payload, JsonArray::class.java) }.getOrNull() ?: return emptyList()
    return root.mapIndexedNotNull { index, element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
        val file = obj.string("file")?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
        val title = obj.string("title")?.let(::htmlText)?.trim()?.takeIf(String::isNotBlank)
        val ordinal = extractSameBandEpisodeNumber(title) ?: (index + 1).toDouble()
        val streams = file.split(',').mapIndexedNotNull streamLoop@ { streamIndex, raw ->
            val item = raw.trim().takeIf(String::isNotBlank) ?: return@streamLoop null
            val quality = QUALITY_PREFIX_REGEX.find(item)?.groupValues?.get(1)?.toIntOrNull()
            val path = item.replace(QUALITY_PREFIX_REGEX, "").trim().takeIf(String::isNotBlank)
                ?: return@streamLoop null
            OnlineStream(
                id = "sameband:${canonicalEpisodeToken(ordinal)}:${quality ?: streamIndex}",
                quality = quality,
                url = path.absoluteUrl("https://sameband.studio"),
                type = OnlineStreamType.HLS,
                headers = mapOf("Referer" to "https://sameband.studio/"),
                translation = "SameBand",
                sourceName = "SameBand",
            )
        }.distinctBy { it.quality to it.url }
        if (streams.isEmpty()) return@mapIndexedNotNull null

        OnlineEpisode(
            providerId = OnlineProviderIds.SAMEBAND,
            id = "sameband:${canonicalEpisodeToken(ordinal)}",
            releaseId = releaseId,
            ordinal = ordinal,
            name = title ?: "${canonicalEpisodeToken(ordinal)} серия",
            previewUrl = null,
            durationMs = 0L,
            sortOrder = ordinal,
            streams = streams.sortedByDescending { it.quality ?: 0 },
        )
    }.sortedBy { it.sortOrder }
}

internal fun parseSameBandRelease(
    id: String,
    document: String,
    episodes: List<OnlineEpisode>,
): OnlineReleaseDetails {
    val title = SAMEBAND_H1_REGEX.find(document)?.groupValues?.get(2)?.let(::htmlText)?.takeIf(String::isNotBlank)
        ?: firstTagText(document, "h1")
        ?: metaContent(document, "og:title")
        ?: id.substringAfterLast('/').substringBeforeLast('.')
    val description = SAMEBAND_DESCRIPTION_REGEX.findAll(document)
        .map { htmlText(it.groupValues[2]) }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)
        ?: metaContent(document, "description", "og:description")
    val poster = SAMEBAND_RELEASE_POSTER_REGEX.find(document)?.groupValues?.get(3)?.let(::decodeHtml)
        ?: metaContent(document, "og:image")
    val visible = htmlText(document)
    val year = YEAR_REGEX.find(visible)?.groupValues?.get(1)?.toIntOrNull()
    val genres = SAMEBAND_GENRE_REGEX.findAll(document)
        .map { htmlText(it.groupValues[2]) }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()

    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.SAMEBAND,
        providerName = "SameBand",
        id = id,
        alias = id.substringAfterLast('/').substringBeforeLast('.'),
        name = title.trim(),
        englishName = null,
        posterUrl = poster?.absoluteUrl("https://sameband.studio"),
        year = year,
        type = null,
        season = null,
        episodeCount = episodes.size,
        description = description,
        notification = "Собственная озвучка SameBand",
        genres = genres,
        isOngoing = false,
        isBlocked = false,
        episodes = episodes,
    )
}

private fun JsonObject.string(name: String): String? = get(name)
    ?.takeUnless { it.isJsonNull }
    ?.asString

private fun extractSameBandEpisodeNumber(title: String?): Double? {
    if (title.isNullOrBlank()) return null
    val candidates = EPISODE_NUMBER_REGEX.findAll(title)
        .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        .toList()
    return candidates.lastOrNull()
}

private fun canonicalEpisodeToken(value: Double): String = if (value % 1.0 == 0.0) {
    value.toInt().toString()
} else {
    value.toString().trimEnd('0').trimEnd('.')
}

private fun fuzzyContains(value: String, query: String): Boolean {
    val normalizedValue = value.lowercase(Locale.ROOT).replace(SEARCH_NOISE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    val normalizedQuery = query.lowercase(Locale.ROOT).replace(SEARCH_NOISE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    return normalizedQuery.isBlank() || normalizedValue.contains(normalizedQuery) ||
        normalizedQuery.split(' ').filter(String::isNotBlank).all(normalizedValue::contains)
}

private fun String?.hasCssClass(value: String): Boolean = this
    ?.split(Regex("\\s+"))
    ?.any { it.equals(value, ignoreCase = true) }
    ?: false

private const val CARD_WINDOW_AFTER = 1400
private const val PLAYER_SCOPE_LENGTH = 3000
private val ANCHOR_TAG_REGEX = Regex("<a\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SAMEBAND_PLAYLIST_REGEX = Regex("Playerjs[^>]+?file\\s*:\\s*([\\\"'])([^\\\"']+)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val QUALITY_PREFIX_REGEX = Regex("^\\[(\\d{3,4})p]", RegexOption.IGNORE_CASE)
private val SAMEBAND_H1_REGEX = Regex("<h1\\b[^>]*class\\s*=\\s*([\\\"'])[^\\\"']*\\bp-0\\b[^\\\"']*\\bm-0\\b[^\\\"']*\\1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SAMEBAND_DESCRIPTION_REGEX = Regex("<div\\b[^>]*class\\s*=\\s*([\\\"'])[^\\\"']*\\blimiter\\b[^\\\"']*\\1[^>]*>.*?<p\\b[^>]*>(.*?)</p>.*?</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SAMEBAND_RELEASE_POSTER_REGEX = Regex("<div\\b[^>]*class\\s*=\\s*([\\\"'])[^\\\"']*\\bimage\\b[^\\\"']*\\1[^>]*>.*?<img\\b[^>]*src\\s*=\\s*([\\\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SAMEBAND_GENRE_REGEX = Regex("<a\\b[^>]*href\\s*=\\s*([\\\"'])[^\\\"']*/genre/[^\\\"']*\\1[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val EPISODE_NUMBER_REGEX = Regex("(?:^|\\D)(\\d+(?:[.,]\\d+)?)(?=\\D|$)")
private val YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b")
private val SEARCH_NOISE_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE_REGEX = Regex("\\s+")
