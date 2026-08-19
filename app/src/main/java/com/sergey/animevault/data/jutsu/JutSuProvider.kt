package com.sergey.animevault.data.jutsu

import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProvider
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.ProviderCapabilities
import com.sergey.animevault.data.online.ProviderSearchMode
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.absoluteUrl
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.firstTagText
import com.sergey.animevault.data.online.htmlStartTags
import com.sergey.animevault.data.online.htmlText
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Request

class JutSuProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.JUT_SU,
        name = "Jut.su",
        description = "Прямые MP4-потоки Jut.su; поиск работает по ссылке или slug",
        isExperimental = true,
        searchHint = "Ссылка Jut.su или slug (например, naruto)",
        capabilities = ProviderCapabilities(
            catalog = false,
            searchMode = ProviderSearchMode.URL_OR_SLUG,
            translations = false,
        ),
    )

    private val pageCache = ConcurrentHashMap<String, String>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        if (page > 1 || search.isBlank()) return OnlineCatalogPage(emptyList(), page, page)
        val slug = search.toJutSlug()
        val document = loadAnimePage(slug)
        val metadata = parseJutSuDetails(slug, document)
        return OnlineCatalogPage(
            releases = listOf(metadata.toCard()),
            currentPage = 1,
            totalPages = 1,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val slug = id.toJutSlug()
        val document = pageCache[slug] ?: loadAnimePage(slug)
        return parseJutSuDetails(slug, document).toDetails()
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> {
        val pageUrl = episode.sourceRef?.absoluteUrl(BASE_URL)
            ?: throw OnlineSourceException("Jut.su: ссылка серии не найдена")
        val document = client.executeText(
            Request.Builder().url(pageUrl).onlineHeaders(
                referer = "$BASE_URL/$releaseId/",
                userAgent = BROWSER_USER_AGENT,
            ).build(),
            descriptor.name,
        )
        val sources = parseJutSuStreams(document, pageUrl)
        return sources.ifEmpty { throw OnlineSourceException("Jut.su не отдал MP4-потоки для этой серии") }
    }

    private suspend fun loadAnimePage(slug: String): String {
        val url = "$BASE_URL/$slug/"
        val rootDocument = client.executeText(
            Request.Builder().url(url).onlineHeaders(referer = BASE_URL, userAgent = BROWSER_USER_AGENT).build(),
            descriptor.name,
        )
        val hasEpisodes = rootDocument.hasJutSuVideoLinks()
        val sectionUrls = if (hasEpisodes) {
            emptyList()
        } else {
            htmlStartTags(rootDocument)
                .asSequence()
                .filter { it.name == "a" }
                .mapNotNull { it.attributes["href"] }
                .filter { href ->
                    JUT_SECTION_PATH_REGEX.matches(href.substringBefore('?'))
                }
                .map { it.absoluteUrl(BASE_URL) }
                .distinct()
                .take(MAX_SECTION_PAGES)
                .toList()
        }
        val sectionDocuments = supervisorScope {
            sectionUrls.map { sectionUrl ->
                async {
                    runCatchingCancellable {
                        client.executeText(
                            Request.Builder().url(sectionUrl).onlineHeaders(
                                referer = url,
                                userAgent = BROWSER_USER_AGENT,
                            ).build(),
                            descriptor.name,
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val document = buildString {
            append(rootDocument)
            sectionDocuments.forEach {
                append('\n')
                append(it)
            }
        }
        if (!document.hasJutSuVideoLinks()) {
            throw OnlineSourceException("Jut.su: аниме '$slug' не найдено. Введите ссылку со страницы Jut.su.")
        }
        pageCache[slug] = document
        return document
    }

    private companion object {
        const val BASE_URL = "https://jut.su"
        const val MAX_SECTION_PAGES = 12
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"
    }
}

private fun String.hasJutSuVideoLinks(): Boolean = htmlStartTags(this).any {
    it.name == "a" && it.attributes["class"]?.split(' ')?.contains("video") == true
}

private val JUT_SECTION_PATH_REGEX = Regex(
    "/[a-z0-9-]+/(?:season-\\d+|film|ova|chibi)/?",
    RegexOption.IGNORE_CASE,
)

internal fun parseJutSuStreams(document: String, pageUrl: String): List<OnlineStream> {
    val playerBody = JUT_PLAYER_REGEX.find(document)?.groupValues?.get(1) ?: return emptyList()
    return htmlStartTags(playerBody)
        .asSequence()
        .filter { it.name == "source" && !it.attributes["src"].isNullOrBlank() }
        .filterNot { tag ->
            tag.attributes["src"].orEmpty().contains("/pixel.png", ignoreCase = true)
        }
        .mapIndexed { index, tag ->
            val quality = tag.attributes["res"]?.filter(Char::isDigit)?.toIntOrNull()
                ?: tag.attributes["label"]?.filter(Char::isDigit)?.toIntOrNull()
            val url = tag.attributes.getValue("src").absoluteUrl("https://jut.su")
            OnlineStream(
                id = tag.attributes["res"] ?: tag.attributes["label"] ?: index.toString(),
                quality = quality,
                url = url,
                type = if (url.contains(".m3u8", ignoreCase = true)) {
                    OnlineStreamType.HLS
                } else {
                    OnlineStreamType.MP4
                },
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://jut.su",
                    "Referer" to pageUrl,
                    "User-Agent" to JUT_STREAM_USER_AGENT,
                ),
                sourceName = "Jut.su",
            )
        }
        .distinctBy(OnlineStream::url)
        .sortedByDescending { it.quality ?: 0 }
        .toList()
}

private val JUT_PLAYER_REGEX = Regex(
    "<video\\b(?=[^>]*\\bid\\s*=\\s*[\"']my-player[\"'])[^>]*>(.*?)</video>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private const val JUT_STREAM_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"

internal data class JutSuMetadata(
    val slug: String,
    val title: String,
    val posterUrl: String?,
    val description: String?,
    val genres: List<String>,
    val year: Int?,
    val episodes: List<OnlineEpisode>,
) {
    fun toCard() = OnlineReleaseCard(
        providerId = OnlineProviderIds.JUT_SU,
        providerName = "Jut.su",
        id = slug,
        alias = slug,
        name = title,
        englishName = null,
        posterUrl = posterUrl,
        year = year,
        type = null,
        season = null,
        episodeCount = episodes.size,
        isOngoing = false,
        genres = genres,
    )

    fun toDetails() = OnlineReleaseDetails(
        providerId = OnlineProviderIds.JUT_SU,
        providerName = "Jut.su",
        id = slug,
        alias = slug,
        name = title,
        englishName = null,
        posterUrl = posterUrl,
        year = year,
        type = null,
        season = null,
        episodeCount = episodes.size,
        description = description,
        notification = "Jut.su не предоставляет обычный поиск: используйте ссылку или slug страницы.",
        genres = genres,
        isOngoing = false,
        isBlocked = false,
        episodes = episodes,
    )
}

internal fun parseJutSuDetails(slug: String, document: String): JutSuMetadata {
    val rawTitle = Regex(
        "<h1\\b[^>]*class=[\"'][^\"']*header_video[^\"']*[\"'][^>]*>(.*?)</h1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(document)?.groupValues?.get(1)?.let(::htmlText) ?: firstTagText(document, "h1") ?: slug
    val title = rawTitle
        .removePrefix("Смотреть ")
        .removeSuffix(" все серии и сезоны")
        .removeSuffix(" все серии")
        .trim()
    val posterTag = htmlStartTags(document).firstOrNull {
        it.attributes["class"]?.split(' ')?.contains("all_anime_title") == true
    }
    val poster = posterTag?.attributes?.get("style")?.let { style ->
        Regex("url\\([\"']?([^\"')]+)").find(style)?.groupValues?.get(1)
    }?.absoluteUrl("https://jut.su")
    val description = Regex(
        "<p\\b[^>]*>(.*?)</p>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(document)?.groupValues?.get(1)?.let(::htmlText)?.takeIf(String::isNotBlank)
    val videoTags = htmlStartTags(document).filter {
        it.name == "a" && it.attributes["class"]?.split(' ')?.contains("video") == true
    }
    val episodes = videoTags.mapIndexed { index, tag ->
        val href = tag.attributes["href"].orEmpty()
        val season = Regex("season-(\\d+)", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)?.toIntOrNull()
        val episodeNumber = Regex("episode-(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val filmNumber = Regex("film-(\\d+)", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.get(1)?.toDoubleOrNull()
        val ordinal = episodeNumber ?: filmNumber ?: (index + 1).toDouble()
        val label = when {
            href.contains("film", ignoreCase = true) -> "Фильм ${filmNumber?.toInt() ?: index + 1}"
            season != null -> "Сезон $season"
            else -> null
        }
        OnlineEpisode(
            providerId = OnlineProviderIds.JUT_SU,
            id = href.trim('/').ifBlank { "$slug:$index" },
            releaseId = slug,
            ordinal = ordinal,
            name = label,
            previewUrl = null,
            durationMs = 0L,
            sortOrder = (season ?: 1) * 10_000.0 + ordinal,
            streams = emptyList(),
            sourceRef = href,
        )
    }.distinctBy(OnlineEpisode::id)
    val year = Regex("/(?:19|20)\\d{2}(?:-(?:19|20)\\d{2})?/")
        .find(document)?.value?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
    val genres = htmlStartTags(document)
        .asSequence()
        .filter { it.name == "a" && it.attributes["href"].orEmpty().contains("/genre", ignoreCase = true) }
        .mapNotNull { it.attributes["title"]?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()
    return JutSuMetadata(slug, title, poster, description, genres, year, episodes)
}

private fun String.toJutSlug(): String {
    val raw = trim()
        .substringAfter("jut.su/", missingDelimiterValue = trim())
        .substringBefore('/')
        .substringBefore('?')
        .trim()
    return raw.lowercase()
        .map { char -> CYRILLIC_TRANSLITERATION[char] ?: char.toString() }
        .joinToString("")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

private val CYRILLIC_TRANSLITERATION = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
    'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
)
