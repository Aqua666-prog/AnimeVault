package com.sergey.animevault.data.kodik

import com.google.gson.Gson
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.htmlStartTags
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64

/**
 * Превращает ссылку на iframe Kodik в прямые HLS-потоки.
 *
 * Алгоритм намеренно отделён от UI: плеер получает обычный m3u8 и ничего не
 * знает о внутреннем API Kodik. Если разметка Kodik изменится, прямые m3u8 от
 * других источников продолжат работать без изменений.
 */
internal class KodikStreamResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val userAgent: String = KODIK_USER_AGENT,
) {
    private val apiPaths = ConcurrentHashMap<String, String>()

    suspend fun resolve(stream: OnlineStream): List<OnlineStream> {
        if (stream.type != OnlineStreamType.EMBED) return listOf(stream)

        val playerUrl = normalizeKodikPlayerUrl(stream.url)
        val parsedUrl = playerUrl.toHttpUrlOrNull()
            ?: throw OnlineSourceException("Kodik вернул некорректную ссылку плеера")
        val origin = parsedUrl.origin()
        val pageHtml = client.executeText(
            Request.Builder()
                .url(playerUrl)
                .get()
                .onlineHeaders(referer = "$origin/", userAgent = userAgent)
                .build(),
            sourceName = "Kodik player",
        )
        val page = parseKodikPlayerPage(pageHtml)

        val responseJson = runCatchingCancellable {
            requestLinks(parsedUrl, playerUrl, page, origin)
        }.getOrElse { firstError ->
            // Путь POST-эндпоинта меняется. Один раз сбрасываем кэш и пробуем
            // снова со свежим player-JS.
            apiPaths.remove(parsedUrl.host)
            runCatchingCancellable { requestLinks(parsedUrl, playerUrl, page, origin) }
                .getOrElse { secondError ->
                    throw OnlineSourceException(
                        "Kodik не отдал прямой HLS-поток",
                        secondError.takeUnless { it === firstError } ?: firstError,
                    )
                }
        }

        return parseKodikLinks(
            responseJson = responseJson,
            template = stream,
            playerUrl = playerUrl,
            gson = gson,
            userAgent = userAgent,
        ).ifEmpty {
            throw OnlineSourceException("Kodik не вернул доступные качества HLS")
        }
    }

    private suspend fun requestLinks(
        parsedUrl: HttpUrl,
        playerUrl: String,
        page: KodikPlayerPage,
        origin: String,
    ): String {
        val apiPath = apiPaths[parsedUrl.host] ?: run {
            val scriptUrl = page.playerJsPath.toAbsoluteUrl(origin)
            val script = client.executeText(
                Request.Builder()
                    .url(scriptUrl)
                    .get()
                    .onlineHeaders(referer = playerUrl, userAgent = userAgent)
                    .build(),
                sourceName = "Kodik player JS",
            )
            extractKodikApiPath(script).also { apiPaths[parsedUrl.host] = it }
        }

        val form = FormBody.Builder().apply {
            page.payload.forEach(::add)
            add("bad_user", "false")
            add("info", "{}")
            add("cdn_is_working", "true")
        }.build()
        return client.executeText(
            Request.Builder()
                .url(origin + apiPath)
                .post(form)
                .onlineHeaders(referer = playerUrl, userAgent = userAgent)
                .header("Origin", origin)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build(),
            sourceName = "Kodik HLS API",
        )
    }
}

internal data class KodikPlayerPage(
    val playerJsPath: String,
    val payload: Map<String, String>,
)

internal fun parseKodikPlayerPage(document: String): KodikPlayerPage {
    fun variable(pattern: String, label: String, allowBlank: Boolean = false): String = Regex(
        pattern,
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(document)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf { allowBlank || it.isNotBlank() }
        ?: throw OnlineSourceException("Kodik изменил параметр плеера: $label")

    val playerJsPath = htmlStartTags(document)
        .asSequence()
        .filter { it.name == "script" }
        .mapNotNull { it.attributes["src"] }
        .firstOrNull { it.contains("assets/js", ignoreCase = true) }
        ?: throw OnlineSourceException("Kodik не указал player-JS")

    return KodikPlayerPage(
        playerJsPath = playerJsPath,
        payload = linkedMapOf(
            "d" to variable("var\\s+domain\\s*=\\s*['\"](.*?)['\"]\\s*;?", "domain"),
            "d_sign" to variable("var\\s+d_sign\\s*=\\s*['\"](.*?)['\"]\\s*;?", "d_sign"),
            "pd" to variable("var\\s+pd\\s*=\\s*['\"](.*?)['\"]\\s*;?", "pd"),
            "pd_sign" to variable("var\\s+pd_sign\\s*=\\s*['\"](.*?)['\"]\\s*;?", "pd_sign"),
            "ref" to variable(
                "var\\s+ref\\s*=\\s*['\"](.*?)['\"]\\s*;?",
                "ref",
                allowBlank = true,
            ),
            "ref_sign" to variable("var\\s+ref_sign\\s*=\\s*['\"](.*?)['\"]\\s*;?", "ref_sign"),
            "type" to variable("vInfo\\s*\\.\\s*type\\s*=\\s*['\"](.*?)['\"]\\s*;?", "type"),
            "hash" to variable("vInfo\\s*\\.\\s*hash\\s*=\\s*['\"](.*?)['\"]\\s*;?", "hash"),
            "id" to variable("vInfo\\s*\\.\\s*id\\s*=\\s*['\"](.*?)['\"]\\s*;?", "id"),
        ),
    )
}

internal fun extractKodikApiPath(playerScript: String): String {
    val encodedPath = Regex(
        "\\$\\.ajax[\\s\\S]*?atob\\(\\s*['\"]([A-Za-z0-9+/=]+)['\"]\\s*\\)",
        RegexOption.IGNORE_CASE,
    ).find(playerScript)?.groupValues?.getOrNull(1)
        ?: throw OnlineSourceException("Kodik не сообщил путь HLS API")
    val decoded = encodedPath.decodeBase64()?.utf8()?.trim()
        ?: throw OnlineSourceException("Kodik вернул повреждённый путь HLS API")
    val path = decoded.let { if (it.startsWith('/')) it else "/$it" }
    if (!path.matches(Regex("/[A-Za-z0-9_./-]+"))) {
        throw OnlineSourceException("Kodik вернул небезопасный путь HLS API")
    }
    return path
}

internal fun parseKodikLinks(
    responseJson: String,
    template: OnlineStream,
    playerUrl: String,
    gson: Gson = Gson(),
    userAgent: String = KODIK_USER_AGENT,
): List<OnlineStream> {
    val envelope = runCatching { gson.fromJson(responseJson, KodikLinksEnvelope::class.java) }
        .getOrElse { throw OnlineSourceException("Kodik вернул повреждённый ответ HLS", it) }
    val parsedPlayerUrl = playerUrl.toHttpUrlOrNull()
        ?: throw OnlineSourceException("Некорректный Referer Kodik")
    val origin = parsedPlayerUrl.origin()
    val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to playerUrl,
        "Origin" to origin,
    )

    return envelope.links.entries
        .mapNotNull { (qualityKey, variants) ->
            val quality = qualityKey.toIntOrNull() ?: return@mapNotNull null
            val encodedSource = variants.firstOrNull()?.src?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val decoded = decodeKodikSource(encodedSource) ?: return@mapNotNull null
            val corrected = if (quality >= 720) {
                decoded.replace("/480.mp4:", "/720.mp4:")
            } else {
                decoded
            }
            OnlineStream(
                id = "${template.id}:hls:$quality",
                quality = quality,
                url = corrected,
                type = OnlineStreamType.HLS,
                headers = headers,
                translation = template.translation,
                sourceName = template.sourceName,
            )
        }
        .sortedByDescending(OnlineStream::quality)
}

internal fun decodeKodikSource(encodedSource: String): String? {
    val source = encodedSource.trim()
    normalizeHttpsUrl(source)?.takeIf { it.contains(".m3u8", ignoreCase = true) }
        ?.let { return it }

    // В старых ответах Kodik применял ROT к Base64. Сначала проверяем известный
    // сдвиг, затем остальные варианты: это дешёво и сохраняет совместимость.
    val shifts = listOf(18) + (0..25).filterNot { it == 18 }
    shifts.forEach { shift ->
        val rotated = rotateLatinLetters(source, shift)
        val decoded = rotated.decodeBase64()?.utf8() ?: return@forEach
        normalizeHttpsUrl(decoded)
            ?.takeIf { it.contains(".m3u8", ignoreCase = true) }
            ?.let { return it }
    }
    return null
}

internal fun normalizeHttpsUrl(rawUrl: String): String? {
    val candidate = when {
        rawUrl.trim().startsWith("//") -> "https:${rawUrl.trim()}"
        rawUrl.trim().startsWith("http://", ignoreCase = true) ->
            "https://${rawUrl.trim().substringAfter("://")}"
        else -> rawUrl.trim()
    }
    val parsed = candidate.toHttpUrlOrNull() ?: return null
    return parsed.takeIf(HttpUrl::isHttps)?.toString()
}

internal fun normalizeKodikPlayerUrl(rawUrl: String): String {
    val normalized = normalizeHttpsUrl(rawUrl)
        ?: throw OnlineSourceException("Kodik вернул незащищённую ссылку плеера")
    val parsed = normalized.toHttpUrlOrNull()
        ?: throw OnlineSourceException("Kodik вернул некорректную ссылку плеера")
    val recognizedHost = KODIK_HOST_MARKERS.any { marker ->
        parsed.host == marker || parsed.host.endsWith(".$marker") || parsed.host.contains(marker)
    }
    val recognizedPath = parsed.pathSegments.firstOrNull()?.lowercase() in KODIK_PLAYER_PATHS
    if (!recognizedHost || !recognizedPath) {
        throw OnlineSourceException("Ссылка не похожа на плеер Kodik")
    }
    return normalized
}

private fun rotateLatinLetters(value: String, shift: Int): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                in 'A'..'Z' -> 'A' + (character - 'A' + shift) % 26
                in 'a'..'z' -> 'a' + (character - 'a' + shift) % 26
                else -> character
            },
        )
    }
}

private fun HttpUrl.origin(): String = newBuilder()
    .encodedPath("/")
    .query(null)
    .fragment(null)
    .build()
    .toString()
    .trimEnd('/')

private fun String.toAbsoluteUrl(origin: String): String = when {
    startsWith("https://") -> this
    startsWith("//") -> "https:$this"
    startsWith('/') -> origin + this
    else -> "$origin/$this"
}

private data class KodikLinksEnvelope(
    val links: Map<String, List<KodikLinkDto>> = emptyMap(),
)

private data class KodikLinkDto(
    val src: String = "",
)

internal val KODIK_USER_AGENT = animeVaultUserAgent("Android; Media3")

private val KODIK_HOST_MARKERS = setOf("kodik.info", "kodikplayer.com", "aniqit.com", "anivod.com")
private val KODIK_PLAYER_PATHS = setOf("serial", "seria", "season", "video", "film")
