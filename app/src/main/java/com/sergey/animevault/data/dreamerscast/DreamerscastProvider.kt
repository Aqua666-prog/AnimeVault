package com.sergey.animevault.data.dreamerscast

import com.google.gson.Gson
import com.google.gson.JsonElement
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64

/**
 * Dream Cast provider.
 *
 * The site exposes its catalogue search as a regular AJAX form response and embeds
 * a PlayerJS JSON payload on release pages. AnimeVault reads only information that
 * the public page itself sends to the browser; no account, DRM or access-control
 * bypass is involved.
 */
class DreamerscastProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val gson: Gson = Gson(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.DREAMERSCAST,
        name = "Dream Cast",
        description = "Собственная русская озвучка Dream Cast и прямые HLS-потоки",
        isExperimental = true,
        searchHint = "Название на русском или английском",
    )

    private val releaseCache = ConcurrentHashMap<String, String>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val responseText = client.executeText(
            Request.Builder()
                .url(BASE_URL + "/")
                .post(
                    FormBody.Builder()
                        .add("search", search.trim())
                        .add("status", "")
                        .add("pageSize", safeLimit.toString())
                        .add("pageNumber", safePage.toString())
                        .build(),
                )
                .onlineHeaders(referer = BASE_URL + "/", userAgent = BROWSER_USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .build(),
            descriptor.name,
        )

        val parsed = parseDreamerscastSearch(responseText, safePage, safeLimit)
        return OnlineCatalogPage(
            releases = parsed.items.map(DreamerscastSearchItem::toCard),
            currentPage = safePage,
            totalPages = parsed.totalPages,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val uri = id.toDreamerscastUri()
        val page = releaseCache[uri] ?: client.executeText(
            Request.Builder()
                .url(uri.absoluteUrl(BASE_URL))
                .get()
                .onlineHeaders(referer = BASE_URL + "/", userAgent = BROWSER_USER_AGENT)
                .build(),
            descriptor.name,
        ).also { releaseCache[uri] = it }

        return parseDreamerscastRelease(uri, page)
    }

    private companion object {
        const val BASE_URL = "https://dreamerscast.com"
        const val MAX_PAGE_SIZE = 60
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

internal data class DreamerscastSearchPage(
    val items: List<DreamerscastSearchItem>,
    val totalPages: Int,
)

internal data class DreamerscastSearchItem(
    val uri: String,
    val russianName: String?,
    val originalName: String?,
    val posterUrl: String?,
    val year: Int?,
    val episodeCount: Int?,
) {
    fun toCard() = OnlineReleaseCard(
        providerId = OnlineProviderIds.DREAMERSCAST,
        providerName = "Dream Cast",
        id = uri,
        alias = uri.substringAfterLast('/').ifBlank { uri },
        name = russianName?.takeIf(String::isNotBlank)
            ?: originalName?.takeIf(String::isNotBlank)
            ?: uri.substringAfterLast('/'),
        englishName = originalName?.takeIf { it.isNotBlank() && it != russianName },
        posterUrl = posterUrl?.absoluteUrl("https://dreamerscast.com"),
        year = year,
        type = null,
        season = null,
        episodeCount = episodeCount,
        isOngoing = false,
        genres = emptyList(),
    )
}

internal fun parseDreamerscastSearch(
    json: String,
    currentPage: Int,
    requestedLimit: Int,
): DreamerscastSearchPage {
    val root = runCatching { Gson().fromJson(json, JsonElement::class.java) }
        .getOrNull()
        ?: throw OnlineSourceException("Dream Cast вернул некорректный ответ каталога")

    val releases = findArray(root, "releases", "items", "data")
        ?: throw OnlineSourceException("Dream Cast изменил формат каталога")

    val items = releases.mapNotNull { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val uri = item.string("url", "uri", "link")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        DreamerscastSearchItem(
            uri = uri,
            russianName = item.string("russian", "rus", "name", "title"),
            originalName = item.string("original", "original_name", "english"),
            posterUrl = item.string("image", "poster", "poster_url"),
            year = item.int("dateissue", "year", "date_issue"),
            episodeCount = item.int("episodes", "episode_count", "episodes_count"),
        )
    }

    val explicitPages = root.findInt("totalPages", "total_pages", "pageCount", "page_count", "pages")
    val totalPages = explicitPages?.coerceAtLeast(currentPage)
        ?: if (items.size >= requestedLimit) currentPage + 1 else currentPage

    return DreamerscastSearchPage(items, totalPages)
}

internal fun parseDreamerscastRelease(uri: String, document: String): OnlineReleaseDetails {
    val playerJson = extractDreamerscastPlayerJson(document)
    val episodes = parseDreamerscastEpisodes(uri, playerJson)
    if (episodes.isEmpty()) {
        throw OnlineSourceException("Dream Cast не отдал серии для этого релиза")
    }

    val pageTitle = firstTagText(document, "h3")
        ?: metaContent(document, "og:title")
        ?: firstTagText(document, "title")
        ?: uri.substringAfterLast('/')
    val splitTitle = pageTitle
        .removeSuffix(" - Dream Cast")
        .trim()
        .split(" / ", limit = 2)
    val visibleText = htmlText(document)
    val year = labelledValue(visibleText, "Год")?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
    val season = labelledValue(visibleText, "Сезон")
    val type = labelledValue(visibleText, "Тип")
    val description = extractDescription(document)
    val genres = extractGenres(document)
    val poster = metaContent(document, "og:image", "twitter:image")
        ?: htmlStartTags(document)
            .firstOrNull { tag ->
                tag.name == "img" && tag.attributes["src"].orEmpty().contains("cache.dreamerscast.com")
            }
            ?.attributes
            ?.get("src")

    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.DREAMERSCAST,
        providerName = "Dream Cast",
        id = uri,
        alias = uri.substringAfterLast('/').ifBlank { uri },
        name = splitTitle.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: pageTitle,
        englishName = splitTitle.getOrNull(1)?.trim()?.takeIf(String::isNotBlank),
        posterUrl = poster?.absoluteUrl("https://dreamerscast.com"),
        year = year,
        type = type,
        season = season,
        episodeCount = episodes.size,
        description = description,
        notification = "Источник Dream Cast: собственная озвучка; качество определяется доступными HLS-вариантами.",
        genres = genres,
        isOngoing = parseEpisodeProgress(visibleText)?.let { (available, total) -> available < total } ?: false,
        isBlocked = false,
        episodes = episodes,
    )
}

internal fun extractDreamerscastPlayerJson(document: String): String {
    val encoded = DREAMERSCAST_PLAYER_REGEX.find(document)?.groupValues?.get(1)
        ?: throw OnlineSourceException("Dream Cast изменил разметку плеера")
    val cleaned = encoded.replace(DREAMERSCAST_BASE64_NOISE_REGEX, "")
    val decoded = cleaned.decodeBase64()?.utf8()
        ?: throw OnlineSourceException("Dream Cast вернул повреждённые данные плеера")
    if (!decoded.trimStart().startsWith("{")) {
        throw OnlineSourceException("Dream Cast изменил кодирование плеера")
    }
    return decoded
}

internal fun parseDreamerscastEpisodes(releaseId: String, playerJson: String): List<OnlineEpisode> {
    val root = runCatching { Gson().fromJson(playerJson, JsonElement::class.java) }.getOrNull()
        ?: return emptyList()
    val files = findArray(root, "file", "files") ?: return emptyList()

    return files.mapIndexedNotNull { index, element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
        val title = item.string("title", "name").orEmpty().ifBlank { "${index + 1} серия" }
        val file = item.string("file", "url", "src").orEmpty()
        val streams = parseDreamerscastStreams(file, releaseId, index)
        if (streams.isEmpty()) return@mapIndexedNotNull null
        val ordinal = DREAMERSCAST_EPISODE_NUMBER_REGEX.find(title)
            ?.groupValues
            ?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?: (index + 1).toDouble()

        OnlineEpisode(
            providerId = OnlineProviderIds.DREAMERSCAST,
            id = "$releaseId:$index:$ordinal",
            releaseId = releaseId,
            ordinal = ordinal,
            name = title,
            previewUrl = null,
            durationMs = 0L,
            sortOrder = ordinal,
            streams = streams,
            sourceRef = null,
        )
    }.sortedBy { it.sortOrder ?: Double.MAX_VALUE }
}

internal fun parseDreamerscastStreams(
    fileValue: String,
    releaseId: String,
    episodeIndex: Int,
): List<OnlineStream> {
    if (fileValue.isBlank()) return emptyList()
    val matches = URL_REGEX.findAll(fileValue).toList()
    return matches.mapIndexedNotNull { index, match ->
        val rawUrl = match.value.trim().trimEnd(',', ';', '"', '\'')
        if (!rawUrl.contains("/hls/", ignoreCase = true) && !rawUrl.contains(".m3u8", ignoreCase = true)) {
            return@mapIndexedNotNull null
        }
        val contextStart = (match.range.first - QUALITY_LOOKBEHIND).coerceAtLeast(0)
        val context = fileValue.substring(contextStart, match.range.first)
        val quality = QUALITY_REGEX.findAll(context).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        OnlineStream(
            id = "dreamerscast:$releaseId:$episodeIndex:$index:${quality ?: 0}",
            quality = quality,
            url = rawUrl,
            type = OnlineStreamType.HLS,
            headers = mapOf(
                "Referer" to "https://dreamerscast.com$releaseId",
                "Origin" to "https://dreamerscast.com",
                "User-Agent" to DREAMERSCAST_STREAM_USER_AGENT,
            ),
            translation = "Dream Cast",
            sourceName = "Dreamerscast",
        )
    }.distinctBy(OnlineStream::url)
        .sortedByDescending { it.quality ?: 0 }
}

private fun extractDescription(document: String): String? {
    val block = Regex(
        "Описание\\s*:\\s*</?[^>]*>?(.*?)(?:<[^>]+(?:comment|player|footer)|Комментарии|$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(document)?.groupValues?.get(1)?.let(::htmlText)?.takeIf { it.length >= 20 }
    if (!block.isNullOrBlank()) return block

    return metaContent(document, "description", "og:description")?.let(::decodeHtml)?.trim()?.takeIf(String::isNotBlank)
}

private fun extractGenres(document: String): List<String> = htmlStartTags(document)
    .asSequence()
    .filter { it.name == "a" }
    .filter { tag ->
        val href = tag.attributes["href"].orEmpty().lowercase(Locale.ROOT)
        href.contains("genre") || href.contains("genres")
    }
    .mapNotNull { tag -> tag.attributes["title"]?.trim()?.takeIf(String::isNotBlank) }
    .distinct()
    .toList()

private fun parseEpisodeProgress(text: String): Pair<Int, Int>? {
    val match = Regex("Эпизодов\\s*:\\s*(\\d+)\\s*из\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        ?: return null
    val available = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    return available to total
}

private fun labelledValue(text: String, label: String): String? = Regex(
    "${Regex.escape(label)}\\s*:\\s*([^|•]+?)(?=\\s+[А-ЯA-Z][А-Яа-яA-Za-z ]{1,18}:|$)",
    RegexOption.IGNORE_CASE,
).find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)

private fun String.toDreamerscastUri(): String {
    val value = trim()
    return when {
        value.startsWith("https://dreamerscast.com/") -> value.removePrefix("https://dreamerscast.com")
        value.startsWith("http://dreamerscast.com/") -> value.removePrefix("http://dreamerscast.com")
        value.startsWith('/') -> value
        value.contains("/home/release/") -> "/" + value.substringAfter("/home/release/").let { "home/release/$it" }
        else -> "/home/release/$value"
    }.substringBefore('#')
}

private fun JsonObject.string(vararg names: String): String? = names.asSequence()
    .mapNotNull { name ->
        get(name)?.takeIf { !it.isJsonNull }?.let { value -> runCatching { value.asString }.getOrNull() }
    }
    .firstOrNull(String::isNotBlank)

private fun JsonObject.int(vararg names: String): Int? = names.asSequence()
    .mapNotNull { name -> get(name)?.takeIf { !it.isJsonNull } }
    .mapNotNull { value ->
        runCatching { value.asInt }.getOrNull()
            ?: runCatching { value.asString.filter(Char::isDigit).toIntOrNull() }.getOrNull()
    }
    .firstOrNull()

private fun JsonElement.findInt(vararg names: String): Int? {
    if (!isJsonObject) return null
    val obj = asJsonObject
    obj.int(*names)?.let { return it }
    return obj.entrySet().asSequence()
        .mapNotNull { (_, value) -> value.takeIf { it.isJsonObject } }
        .mapNotNull { child -> child.findInt(*names) }
        .firstOrNull()
}

private fun findArray(root: JsonElement, vararg names: String): List<JsonElement>? {
    if (root.isJsonArray) return root.asJsonArray.toList()
    if (!root.isJsonObject) return null
    val obj = root.asJsonObject
    names.forEach { name ->
        obj.get(name)?.takeIf { it.isJsonArray }?.let { return it.asJsonArray.toList() }
    }
    obj.entrySet().forEach { (_, value) ->
        if (value.isJsonObject) {
            findArray(value, *names)?.let { return it }
        }
    }
    return null
}

private val DREAMERSCAST_PLAYER_REGEX = Regex(
    "Playerjs\\(\\\"#2(.*?)\\\"\\);",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val DREAMERSCAST_BASE64_NOISE_REGEX = Regex("//[^=]+==")
private val DREAMERSCAST_EPISODE_NUMBER_REGEX = Regex("(\\d+(?:[.,]\\d+)?)")
private val URL_REGEX = Regex("https?://[^\\s\\],}]+", RegexOption.IGNORE_CASE)
private val QUALITY_REGEX = Regex("(?:\\[|\\b)(360|480|540|720|1080|1440|2160)p?(?:\\]|\\b)", RegexOption.IGNORE_CASE)
private const val QUALITY_LOOKBEHIND = 24
private const val DREAMERSCAST_STREAM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
