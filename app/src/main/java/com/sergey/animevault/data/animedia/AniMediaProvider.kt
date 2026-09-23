package com.sergey.animevault.data.animedia

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
import com.sergey.animevault.data.online.htmlText
import com.sergey.animevault.data.online.metaContent
import com.sergey.animevault.data.online.onlineHeaders
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Public AniMedia (amd.online) catalogue and playback adapter.
 *
 * The implementation follows the same ordinary browser flow as the site:
 * catalogue/search HTML -> release page data-vlnk -> public VOD page -> HLS URL.
 */
class AniMediaProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIMEDIA,
        name = "AniMedia",
        description = "Каталог AniMedia и HLS-потоки примерно до 720p",
        isExperimental = true,
        searchHint = "Название аниме в AniMedia",
    )

    private val releaseCache = ConcurrentHashMap<String, String>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val query = search.trim()
        if (query.isNotBlank() && safePage > 1) {
            // The site's DLE search endpoint used by the upstream adapter returns
            // a single result page. Do not pretend later pages exist.
            return OnlineCatalogPage(emptyList(), safePage, safePage)
        }

        val document = if (query.isBlank()) {
            val url = if (safePage == 1) BASE_URL + "/" else "$BASE_URL/page/$safePage/"
            client.executeText(
                Request.Builder()
                    .url(url)
                    .get()
                    .onlineHeaders(referer = BASE_URL + "/")
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
                            .add("from_page", "0")
                            .add("story", query)
                            .build(),
                    )
                    .onlineHeaders(referer = BASE_URL + "/")
                    .build(),
                descriptor.name,
            )
        }

        val parsed = parseAniMediaCatalog(document)
        val cards = parsed.items
            .filter { query.isBlank() || fuzzyContains(it.name, query) }
            .take(limit.coerceAtLeast(1))
            .map(AniMediaCatalogItem::toCard)

        return OnlineCatalogPage(
            releases = cards,
            currentPage = safePage,
            totalPages = if (query.isBlank()) parsed.totalPages.coerceAtLeast(safePage) else safePage,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val path = id.toAniMediaPath()
        val document = releaseCache[path] ?: client.executeText(
            Request.Builder()
                .url(path.absoluteUrl(BASE_URL))
                .get()
                .onlineHeaders(referer = BASE_URL + "/")
                .build(),
            descriptor.name,
        ).also { releaseCache[path] = it }
        return parseAniMediaRelease(path, document)
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> {
        if (episode.streams.isNotEmpty()) return episode.streams
        val vod = episode.sourceRef?.takeIf(String::isNotBlank)
            ?: throw OnlineSourceException("AniMedia: ссылка на плеер серии не найдена")
        val document = client.executeText(
            Request.Builder()
                .url(vod.absoluteUrl(BASE_URL))
                .get()
                .onlineHeaders(referer = releaseId.toAniMediaPath().absoluteUrl(BASE_URL))
                .build(),
            descriptor.name,
        )
        val hls = parseAniMediaVodHls(document)
            ?: throw OnlineSourceException("AniMedia не отдал HLS для этой серии")
        val vodUrl = vod.absoluteUrl(BASE_URL)
        val hlsBase = vodUrl.substringBeforeLast('/', vodUrl)
        return listOf(
            OnlineStream(
                id = "animedia:${episode.id}:720",
                quality = 720,
                url = hls.absoluteUrl(hlsBase),
                type = OnlineStreamType.HLS,
                headers = mapOf(
                    "Referer" to vodUrl,
                ),
                translation = "AniMedia",
                sourceName = "AniMedia",
            ),
        )
    }

    private companion object {
        const val BASE_URL = "https://amd.online"
    }
}

internal data class AniMediaCatalogPage(
    val items: List<AniMediaCatalogItem>,
    val totalPages: Int,
)

internal data class AniMediaCatalogItem(
    val path: String,
    val name: String,
    val posterUrl: String?,
    val episodeCount: Int?,
    val isOngoing: Boolean,
) {
    fun toCard() = OnlineReleaseCard(
        providerId = OnlineProviderIds.ANIMEDIA,
        providerName = "AniMedia",
        id = path,
        alias = path.substringAfterLast('/').substringBeforeLast('.'),
        name = name,
        englishName = null,
        posterUrl = posterUrl?.absoluteUrl("https://amd.online"),
        year = YEAR_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull(),
        type = null,
        season = null,
        episodeCount = episodeCount,
        isOngoing = isOngoing,
        genres = emptyList(),
    )
}

internal fun parseAniMediaCatalog(document: String): AniMediaCatalogPage {
    val items = ANIMEDIA_CARD_REGEX.findAll(document).mapNotNull { match ->
        val path = match.groupValues[1].trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = decodeHtml(match.groupValues[2]).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val contextStart = (match.range.first - CARD_CONTEXT_BEFORE).coerceAtLeast(0)
        val contextEnd = (match.range.last + CARD_CONTEXT_AFTER).coerceAtMost(document.lastIndex)
        val context = document.substring(contextStart, contextEnd + 1)
        val poster = ANIMEDIA_IMAGE_REGEX.findAll(context).lastOrNull()?.groupValues?.get(1)
        val progress = EPISODE_PROGRESS_REGEX.find(context)
        val availableEpisodes = progress?.groupValues?.get(1)?.toIntOrNull()
        val totalEpisodes = progress?.groupValues?.get(2)?.toIntOrNull()
        AniMediaCatalogItem(
            path = "/" + path.trimStart('/'),
            name = name,
            posterUrl = poster?.let(::decodeHtml),
            episodeCount = totalEpisodes ?: availableEpisodes,
            isOngoing = totalEpisodes != null && availableEpisodes != null && availableEpisodes < totalEpisodes,
        )
    }.distinctBy(AniMediaCatalogItem::path).toList()

    val totalPages = ANIMEDIA_PAGE_REGEX.findAll(document)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .maxOrNull()
        ?: 1

    return AniMediaCatalogPage(items, totalPages)
}

internal fun parseAniMediaRelease(path: String, document: String): OnlineReleaseDetails {
    val title = firstTagText(document, "h1")
        ?: metaContent(document, "og:title")
        ?: firstTagText(document, "title")
        ?: path.substringAfterLast('/')
    val cleanTitle = title
        .replace(Regex("\\s*[-|]\\s*(?:AniMedia|Animedia).*?$", RegexOption.IGNORE_CASE), "")
        .trim()
    val visible = htmlText(document)
    val seasonNumber = ANIMEDIA_SEASON_REGEX.find(visible)?.groupValues?.get(1)?.toIntOrNull()
    val year = YEAR_REGEX.find(cleanTitle)?.groupValues?.get(1)?.toIntOrNull()
        ?: LABELLED_YEAR_REGEX.find(visible)?.groupValues?.get(1)?.toIntOrNull()
    val poster = metaContent(document, "og:image", "twitter:image")
        ?: ANIMEDIA_RELEASE_POSTER_REGEX.find(document)?.groupValues?.get(1)?.let(::decodeHtml)
    val description = metaContent(document, "description", "og:description")
        ?.let(::decodeHtml)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: ANIMEDIA_DESCRIPTION_REGEX.find(document)?.groupValues?.get(1)?.let(::htmlText)?.takeIf(String::isNotBlank)
    val genres = ANIMEDIA_GENRE_REGEX.findAll(document)
        .map { decodeHtml(it.groupValues[1]).trim() }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()
    val episodes = parseAniMediaEpisodes(path, document, seasonNumber ?: 1)
    if (episodes.isEmpty()) {
        throw OnlineSourceException("AniMedia не отдал серии для этого релиза")
    }

    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.ANIMEDIA,
        providerName = "AniMedia",
        id = path,
        alias = path.substringAfterLast('/').substringBeforeLast('.'),
        name = cleanTitle,
        englishName = null,
        posterUrl = poster?.absoluteUrl("https://amd.online"),
        year = year,
        type = detectAniMediaType(visible),
        season = seasonNumber?.let { "Сезон $it" },
        episodeCount = episodes.size,
        description = description,
        notification = "AniMedia: публичный HLS-плеер; фактическое качество определяется источником.",
        genres = genres,
        isOngoing = detectAniMediaOngoing(visible),
        isBlocked = false,
        episodes = episodes,
    )
}

internal fun parseAniMediaEpisodes(
    releaseId: String,
    document: String,
    season: Int = 1,
): List<OnlineEpisode> = ANIMEDIA_EPISODE_REGEX.findAll(document)
    .mapNotNull { match ->
        val ordinal = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
        val vod = decodeHtml(match.groupValues[2]).trim()
        if (vod.isBlank() || !vod.contains("/vod/", ignoreCase = true)) return@mapNotNull null
        OnlineEpisode(
            providerId = OnlineProviderIds.ANIMEDIA,
            id = "animedia:$releaseId:${canonicalEpisodeNumber(ordinal)}",
            releaseId = releaseId,
            ordinal = ordinal,
            name = "${canonicalEpisodeNumber(ordinal)} серия",
            previewUrl = null,
            durationMs = 0L,
            sortOrder = season * 10_000.0 + ordinal,
            streams = emptyList(),
            sourceRef = vod,
        )
    }
    .distinctBy { it.ordinal }
    .sortedBy { it.ordinal }
    .toList()

internal fun parseAniMediaVodHls(document: String): String? = ANIMEDIA_HLS_REGEX.find(document)
    ?.groupValues
    ?.get(1)
    ?.let(::decodeHtml)
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun String.toAniMediaPath(): String {
    val value = trim()
    return when {
        value.startsWith("https://amd.online/") -> "/" + value.removePrefix("https://amd.online/")
        value.startsWith("http://amd.online/") -> "/" + value.removePrefix("http://amd.online/")
        value.startsWith('/') -> value
        else -> "/$value"
    }.substringBefore('#')
}

private fun fuzzyContains(name: String, query: String): Boolean {
    val normalizedName = name.lowercase(Locale.ROOT).replace('ё', 'е')
    val normalizedQuery = query.lowercase(Locale.ROOT).replace('ё', 'е')
    return normalizedQuery.split(Regex("\\s+")).filter(String::isNotBlank).all(normalizedName::contains)
}

private fun detectAniMediaType(text: String): String? = when {
    text.contains("TV сериал", ignoreCase = true) -> "TV-сериал"
    Regex("\\bOVA\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "OVA"
    Regex("\\bONA\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "ONA"
    text.contains("Фильм", ignoreCase = true) -> "Фильм"
    else -> null
}

private fun detectAniMediaOngoing(text: String): Boolean =
    text.contains("Онгоинг", ignoreCase = true) || EPISODE_PROGRESS_REGEX.find(text)?.let { progress ->
        val available = progress.groupValues[1].toIntOrNull()
        val total = progress.groupValues[2].toIntOrNull()
        available != null && total != null && available < total
    } == true

private fun canonicalEpisodeNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private val ANIMEDIA_CARD_REGEX = Regex(
    "<a\\s+href=[\"']https?://[^/]+/([^\"']+)[\"']\\s+class=[\"'][^\"']*poster__link[^\"']*[\"'][^>]*>\\s*<h3\\s+class=[\"'][^\"']*poster__title[^\"']*[\"'][^>]*>([^<]+)</h3>\\s*</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANIMEDIA_IMAGE_REGEX = Regex("<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val ANIMEDIA_PAGE_REGEX = Regex("href=[\"'][^\"']*/page/(\\d+)/?[\"']", RegexOption.IGNORE_CASE)
private val ANIMEDIA_EPISODE_REGEX = Regex(
    "data-vid=[\"'](\\d+(?:[.,]\\d+)?)[\"']\\s+data-vlnk=[\"']([^\"']+)[\"']",
    RegexOption.IGNORE_CASE,
)
private val ANIMEDIA_HLS_REGEX = Regex("file\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val ANIMEDIA_SEASON_REGEX = Regex("Season\\s+(\\d+)", RegexOption.IGNORE_CASE)
private val EPISODE_PROGRESS_REGEX = Regex("(\\d+)\\s*из\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b")
private val LABELLED_YEAR_REGEX = Regex("(?:Год|Year)\\s*:?\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
private val ANIMEDIA_GENRE_REGEX = Regex(
    "<a\\b[^>]*href=[\"'][^\"']*(?:/genre/|genre=)[^\"']*[\"'][^>]*>([^<]+)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANIMEDIA_RELEASE_POSTER_REGEX = Regex(
    "<img\\b[^>]*class=[\"'][^\"']*(?:pmovie__poster|poster)[^\"']*[\"'][^>]*src=[\"']([^\"']+)[\"']",
    RegexOption.IGNORE_CASE,
)
private val ANIMEDIA_DESCRIPTION_REGEX = Regex(
    "(?:Описание|description)[^>]*>\\s*(.*?)(?:</div>|</section>)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private const val CARD_CONTEXT_BEFORE = 900
private const val CARD_CONTEXT_AFTER = 700
