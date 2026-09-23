package com.sergey.animevault.data.animebest

import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProvider
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.ProviderCapabilities
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
import com.sergey.animevault.util.runCatchingCancellable
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeBest search and multi-voice playback adapter.
 *
 * The public title page exposes a videoList where each item pairs an episode
 * with a voice label and an iframe. Iframes are resolved lazily to HLS only
 * when the user starts an episode, avoiding dozens of network requests while
 * merely browsing a title.
 */
class AnimeBestProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIME_BEST,
        name = "AnimeBest",
        description = "Большой каталог AnimeBest с несколькими вариантами озвучки",
        isExperimental = true,
        healthProbeQuery = "Naruto",
        searchHint = "Название аниме в AnimeBest",
        capabilities = ProviderCapabilities(catalog = false),
    )

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val query = search.trim()
        if (query.isBlank() || safePage > 1) {
            // Search is the stable contract used by the current upstream parser.
            // Browse pagination can be added independently without risking search/playback.
            return OnlineCatalogPage(emptyList(), safePage, safePage)
        }

        val body = client.executeText(
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
        val cards = parseAnimeBestSearch(body)
            .filter { fuzzyContains(it.name, query) }
            .take(limit.coerceAtLeast(1))
            .map(AnimeBestSearchItem::toCard)
        return OnlineCatalogPage(cards, 1, 1)
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val releaseUrl = id.toAnimeBestUrl()
        val body = client.executeText(
            Request.Builder()
                .url(releaseUrl)
                .get()
                .onlineHeaders(referer = "$BASE_URL/")
                .build(),
            descriptor.name,
        )
        return parseAnimeBestRelease(releaseUrl, body)
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> = supervisorScope {
        if (episode.streams.none { it.type == OnlineStreamType.EMBED }) return@supervisorScope episode.streams
        val semaphore = Semaphore(MAX_PARALLEL_RESOLVERS)
        episode.streams.map { stream ->
            async {
                if (stream.type != OnlineStreamType.EMBED) return@async stream
                semaphore.withPermit {
                    runCatchingCancellable { resolveEmbed(stream, releaseId.toAnimeBestUrl()) }.getOrElse { stream }
                }
            }
        }.awaitAll()
    }

    private suspend fun resolveEmbed(stream: OnlineStream, releaseUrl: String): OnlineStream {
        val iframeUrl = stream.url.toAnimeBestEmbedUrl()
        val body = client.executeText(
            Request.Builder()
                .url(iframeUrl)
                .get()
                .onlineHeaders(referer = releaseUrl)
                .build(),
            descriptor.name,
        )
        val hls = parseAnimeBestHls(body)
            ?: throw OnlineSourceException("AnimeBest не отдал HLS для ${stream.translation ?: "этой озвучки"}")
        return stream.copy(
            quality = stream.quality ?: 720,
            url = hls,
            type = OnlineStreamType.HLS,
            headers = mapOf("Referer" to iframeUrl),
        )
    }

    private companion object {
        const val BASE_URL = "https://b1.animebesst.org"
        const val MAX_PARALLEL_RESOLVERS = 4
    }
}

internal data class AnimeBestSearchItem(
    val name: String,
    val year: Int?,
    val url: String,
    val season: Int?,
    val posterUrl: String?,
) {
    fun toCard() = OnlineReleaseCard(
        providerId = OnlineProviderIds.ANIME_BEST,
        providerName = "AnimeBest",
        id = url,
        alias = url.substringAfterLast('/').substringBeforeLast('.'),
        name = name,
        englishName = null,
        posterUrl = posterUrl?.absoluteUrl("https://b1.animebesst.org"),
        year = year,
        type = null,
        season = season?.takeIf { it > 0 }?.let { "Сезон $it" },
        episodeCount = null,
        isOngoing = false,
        genres = emptyList(),
    )
}

internal data class AnimeBestVideoEntry(
    val episode: Double,
    val voice: String?,
    val embedUrl: String,
)

internal fun parseAnimeBestSearch(document: String): List<AnimeBestSearchItem> = ANIMEBEST_SEARCH_BLOCK_REGEX
    .findAll(document)
    .mapNotNull { blockMatch ->
        val block = blockMatch.groupValues[2]
        if (block.contains("Новости", ignoreCase = true)) return@mapNotNull null
        val titleMatch = ANIMEBEST_TITLE_LINK_REGEX.find(block) ?: return@mapNotNull null
        val url = decodeHtml(titleMatch.groupValues[3]).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = decodeHtml(titleMatch.groupValues[4]).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val season = SEASON_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (name.contains("сезон", ignoreCase = true)) 1 else null
        val year = ANIMEBEST_YEAR_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: YEAR_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
        val poster = ANIMEBEST_POSTER_REGEX.find(block)?.groupValues?.get(2)?.let(::decodeHtml)
        AnimeBestSearchItem(name, year, url, season, poster)
    }
    .distinctBy { it.url }
    .toList()

internal fun parseAnimeBestVideoList(document: String): List<AnimeBestVideoEntry> {
    val expression = VIDEO_LIST_ASSIGNMENT_REGEX.find(document)?.groupValues?.get(1) ?: return emptyList()
    return ANIMEBEST_VIDEO_REGEX.findAll(expression).mapNotNull { match ->
        val episode = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
        val voice = match.groupValues[2]
            .trim()
            .removeSurrounding("(", ")")
            .let(::decodeJsonUnicode)
            .let(::decodeHtml)
            .trim()
            .takeIf(String::isNotBlank)
        val hostAndPath = match.groupValues[4]
            .replace("\\/", "/")
            .replace("\\", "")
            .trim()
            .takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        AnimeBestVideoEntry(
            episode = episode,
            voice = voice,
            embedUrl = "https://$hostAndPath",
        )
    }.toList()
}

internal fun parseAnimeBestRelease(id: String, document: String): OnlineReleaseDetails {
    val title = firstTagText(document, "h1")
        ?: metaContent(document, "og:title")
        ?: firstTagText(document, "title")
        ?: id.substringAfterLast('/').substringBeforeLast('.')
    val visible = htmlText(document)
    val entries = parseAnimeBestVideoList(document)
    val grouped = entries.groupBy(AnimeBestVideoEntry::episode)
    val episodes = grouped.map { (number, variants) ->
        OnlineEpisode(
            providerId = OnlineProviderIds.ANIME_BEST,
            id = "animebest:${episodeToken(number)}",
            releaseId = id,
            ordinal = number,
            name = "${episodeToken(number)} серия",
            previewUrl = null,
            durationMs = 0L,
            sortOrder = number,
            streams = variants.mapIndexed { index, variant ->
                OnlineStream(
                    id = "animebest:${episodeToken(number)}:$index",
                    quality = null,
                    url = variant.embedUrl,
                    type = OnlineStreamType.EMBED,
                    headers = mapOf("Referer" to id),
                    translation = variant.voice ?: "AnimeBest",
                    sourceName = "AnimeBest",
                )
            }.distinctBy { it.url to it.translation },
        )
    }.sortedBy { it.ordinal }

    if (episodes.isEmpty()) throw OnlineSourceException("AnimeBest не отдал список серий")

    val poster = metaContent(document, "og:image", "twitter:image")
    val description = metaContent(document, "description", "og:description")?.let(::decodeHtml)?.trim()?.takeIf(String::isNotBlank)
    val year = LABELLED_YEAR_REGEX.find(visible)?.groupValues?.get(1)?.toIntOrNull()
        ?: YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
    val season = SEASON_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
    // The current page mixes navigation and metadata anchors. Avoid treating
    // arbitrary navigation labels as genres until a stable genre container is identified.
    val genres = emptyList<String>()
    val voices = entries.mapNotNull(AnimeBestVideoEntry::voice).distinct()

    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.ANIME_BEST,
        providerName = "AnimeBest",
        id = id,
        alias = id.substringAfterLast('/').substringBeforeLast('.'),
        name = title.trim(),
        englishName = null,
        posterUrl = poster,
        year = year,
        type = null,
        season = season?.let { "Сезон $it" },
        episodeCount = episodes.size,
        description = description,
        notification = voices.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Озвучки: "),
        genres = genres,
        isOngoing = false,
        isBlocked = false,
        episodes = episodes,
    )
}

internal fun parseAnimeBestHls(document: String): String? = ANIMEBEST_HLS_REGEX
    .find(document)
    ?.groupValues
    ?.get(1)
    ?.replace("\\/", "/")
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun String.toAnimeBestUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    else -> absoluteUrl("https://b1.animebesst.org")
}

private fun String.toAnimeBestEmbedUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    else -> "https://" + trimStart('/').removePrefix("//")
}

private fun decodeJsonUnicode(value: String): String = JSON_UNICODE_REGEX.replace(value) { match ->
    match.groupValues[1].toIntOrNull(16)?.let { code -> code.toChar().toString() } ?: match.value
}

private fun episodeToken(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun fuzzyContains(value: String, query: String): Boolean {
    val normalizedValue = value.lowercase(Locale.ROOT).replace(SEARCH_NOISE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    val normalizedQuery = query.lowercase(Locale.ROOT).replace(SEARCH_NOISE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    return normalizedQuery.isBlank() || normalizedValue.contains(normalizedQuery) ||
        normalizedQuery.split(' ').filter(String::isNotBlank).all(normalizedValue::contains)
}

private val ANIMEBEST_SEARCH_BLOCK_REGEX = Regex(
    "class\\s*=\\s*([\\\"'])shortstory-listab\\1[^>]*>(.*?)(?=class\\s*=\\s*([\\\"'])shortstory-listab\\3|id\\s*=\\s*([\\\"'])sidebar\\4|$)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANIMEBEST_TITLE_LINK_REGEX = Regex(
    "class\\s*=\\s*([\\\"'])shortstory-listab-title\\1[^>]*>\\s*<a\\b[^>]*href\\s*=\\s*([\\\"'])(https?://[^\\\"']+\\.html)\\2[^>]*>([^<]+)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val ANIMEBEST_POSTER_REGEX = Regex("<img\\b[^>]*(?:data-src|src)\\s*=\\s*([\\\"'])([^\\\"']+)\\1", RegexOption.IGNORE_CASE)
private val ANIMEBEST_YEAR_REGEX = Regex(">\\s*((?:19|20)\\d{2})\\s*</a>", RegexOption.IGNORE_CASE)
private val VIDEO_LIST_ASSIGNMENT_REGEX = Regex("var\\s+videoList\\s*=\\s*([^\\n\\r]+)", RegexOption.IGNORE_CASE)
private val ANIMEBEST_VIDEO_REGEX = Regex("\\\"id\\\":\\\"([0-9]+)( [^\\\"]+)?\\\",\\\"link\\\":\\\"(https?:)?(?:\\\\/){2}([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
private val ANIMEBEST_HLS_REGEX = Regex("file\\s*:\\s*[\\\"'](https?://[^\\\"']+\\.m3u8[^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
private val LABELLED_YEAR_REGEX = Regex("Год выпуска\\s*:?\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
private val SEASON_REGEX = Regex("([0-9]+)\\s*сезон", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b")
private val ANIMEBEST_GENRE_REGEX = Regex("<a\\b[^>]*href\\s*=\\s*([\\\"'])[^\\\"']+\\1[^>]*>([^<]+)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val JSON_UNICODE_REGEX = Regex("\\\\u([0-9a-fA-F]{4})")
private val SEARCH_NOISE_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE_REGEX = Regex("\\s+")
