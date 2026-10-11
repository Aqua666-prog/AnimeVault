package com.sergey.animevault.data.kodik

import android.content.Context
import android.util.Log
import com.sergey.animevault.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.ProviderAccountState
import com.sergey.animevault.data.online.ProviderAuthMode
import com.sergey.animevault.data.online.ProviderCapabilities
import com.sergey.animevault.data.online.SecureSessionStore
import com.sergey.animevault.data.online.TokenOnlineProvider
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.util.runCatchingCancellable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class KodikProvider internal constructor(
    context: Context,
    private val api: KodikApi = KodikApi(),
    private val streamResolver: KodikStreamResolver = KodikStreamResolver(),
) : TokenOnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.KODIK,
        name = "Kodik",
        description = "Много озвучек и субтитров в одном релизе",
        authMode = ProviderAuthMode.OPTIONAL_TOKEN,
        isExperimental = true,
        searchHint = "Название на русском, английском или японском",
        capabilities = ProviderCapabilities(
            translations = true,
            subtitles = true,
        ),
    )

    private val sessions = SecureSessionStore(context)
    private val pageUrls = mutableMapOf<String, MutableMap<Int, String>>()
    private val authorization = KodikTokenFallback(
        personalToken = { token() },
        bundledToken = { BuildConfig.KODIK_API_TOKEN },
        publicToken = api::publicToken,
    )

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val query = search.trim()
        val response = authorization.execute { token ->
            if (query.isNotBlank()) {
                api.search(
                token = token,
                parameters = mapOf(
                    "title" to query,
                    "limit" to MAX_SEARCH_RESULTS.toString(),
                    "types" to ANIME_TYPES,
                    "with_material_data" to "true",
                    "not_blocked_for_me" to "true",
                ),
                )
            } else {
                val pages = pageUrls.getOrPut(CATALOG_CACHE_KEY) { mutableMapOf() }
                val pageUrl = if (page <= 1) null else pages[page]
                if (page > 1 && pageUrl == null) {
                    return@execute KodikResponseDto()
                }
                api.list(
                token = token,
                pageUrl = pageUrl,
                parameters = mapOf(
                    "limit" to CATALOG_API_LIMIT.toString(),
                    "types" to ANIME_TYPES,
                    "with_material_data" to "true",
                    "not_blocked_for_me" to "true",
                    "sort" to "updated_at",
                    "order" to "desc",
                ),
            ).also { result ->
                if (page <= 1) pages.clear()
                    result.nextPage?.takeIf(String::isNotBlank)?.let { pages[page + 1] = it }
                }
            }
        }

        val cards = response.results.toReleaseCards()
        val hasNextPage = query.isBlank() && !response.nextPage.isNullOrBlank()
        return OnlineCatalogPage(
            releases = cards,
            currentPage = page.coerceAtLeast(1),
            totalPages = if (hasNextPage) page.coerceAtLeast(1) + 1 else page.coerceAtLeast(1),
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val reference = KodikReleaseReference.parse(id)
        val baseParameters = buildMap {
            put(reference.queryName, reference.queryValue)
            put("limit", MAX_SEARCH_RESULTS.toString())
            put("types", ANIME_TYPES)
            put("with_material_data", "true")
            put("with_episodes_data", "true")
            put("not_blocked_for_me", "true")
            // Отключаем стандартные приоритеты Kodik: нам нужен максимально
            // полный набор переводов, а ранжирование сделает AnimeVault.
            put("prioritize_translations", "0")
            put("unprioritize_translations", "0")
        }
        val responses = authorization.execute { token ->
            val initial = api.search(
                token = token,
                parameters = baseParameters,
            )

        // У общего search есть собственное ранжирование переводов. Даже когда
        // ответ не упёрся в limit, отдельные voice/subtitles запросы могут вернуть
        // варианты, которых нет в общей выдаче. Объединяем все три ответа.
        val typed = supervisorScope {
            listOf("voice", "subtitles").map { translationType ->
                async {
                    runCatchingCancellable {
                        api.search(
                            token = token,
                            parameters = baseParameters + ("translation_type" to translationType),
                        )
                    }.onFailure { error ->
                        Log.w(LOG_TAG, "Не удалось дополнительно загрузить $translationType", error)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
            listOf(initial) + typed
        }

        val variants = mergeKodikReleaseVariants(reference, responses)
        if (variants.isEmpty()) throw OnlineSourceException("Kodik не нашёл этот релиз")
        return variants.toReleaseDetails(reference)
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> = supervisorScope {
        val concurrency = Semaphore(4)
        episode.streams.map { stream ->
            async {
                if (stream.type != OnlineStreamType.EMBED) {
                    listOf(stream)
                } else {
                    concurrency.withPermit {
                        runCatchingCancellable { streamResolver.resolve(stream) }
                            .getOrElse { error ->
                                // WebView остаётся последним резервом, если Kodik
                                // поменял разметку или временно не вернул HLS.
                                Log.w(LOG_TAG, "Не удалось извлечь HLS для ${stream.displayName}", error)
                                listOf(stream)
                            }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    override fun accountState() = ProviderAccountState(
        providerId = OnlineProviderIds.KODIK,
        isSignedIn = !token().isNullOrBlank(),
        displayName = if (!token().isNullOrBlank()) "API-токен сохранён" else null,
    )

    override fun setToken(token: String) {
        sessions.put(TOKEN_KEY, token.trim().removePrefix("Bearer ").trim())
        pageUrls.clear()
    }

    override fun signOut() {
        sessions.put(TOKEN_KEY, null)
        pageUrls.clear()
    }

    private fun token(): String? = sessions.get(TOKEN_KEY)

    private companion object {
        const val TOKEN_KEY = "kodik.token"
        const val CATALOG_CACHE_KEY = "latest"
        const val CATALOG_API_LIMIT = 60
        const val MAX_SEARCH_RESULTS = 100
        const val ANIME_TYPES = "anime,anime-serial"
        const val LOG_TAG = "KodikProvider"
    }
}

internal class KodikApi(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val gson: Gson = Gson(),
) {
    private val publicTokenMutex = Mutex()
    private var cachedPublicToken: String? = null
    private var publicTokenCachedAtMs: Long = 0L

    suspend fun publicToken(forceRefresh: Boolean = false): String {
        publicTokenMutex.lock()
        try {
            val now = System.currentTimeMillis()
            if (!forceRefresh && now - publicTokenCachedAtMs < PUBLIC_TOKEN_TTL_MS) {
                cachedPublicToken?.takeIf(String::isNotBlank)?.let { return it }
            }
            val script = client.executeText(
                Request.Builder()
                    .url(PUBLIC_PLAYERS_SCRIPT_URL)
                    .get()
                    .onlineHeaders(referer = "https://kodik.info/")
                    .build(),
                sourceName = "Kodik",
            )
            val fresh = extractKodikPublicToken(script)
                ?: throw OnlineSourceException("Kodik не опубликовал API-токен в скрипте плеера")
            cachedPublicToken = fresh
            publicTokenCachedAtMs = System.currentTimeMillis()
            return fresh
        } finally {
            publicTokenMutex.unlock()
        }
    }

    suspend fun list(
        token: String,
        pageUrl: String?,
        parameters: Map<String, String>,
    ): KodikResponseDto = request(
        url = pageUrl?.validatedKodikPageUrl(token) ?: endpoint("list", token, parameters),
    )

    suspend fun search(token: String, parameters: Map<String, String>): KodikResponseDto =
        request(endpoint("search", token, parameters))

    private suspend fun request(url: String): KodikResponseDto {
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .onlineHeaders(referer = "https://kodik.info/")
                .build(),
            sourceName = "Kodik",
        )
        val response = runCatching { gson.fromJson(body, KodikResponseDto::class.java) }
            .getOrElse { throw OnlineSourceException("Kodik вернул повреждённый ответ", it) }
        response.error?.takeIf(String::isNotBlank)?.let { error ->
            throw OnlineSourceException("Kodik: ${error.take(180)}")
        }
        return response
    }

    private fun endpoint(
        path: String,
        token: String,
        parameters: Map<String, String>,
    ): String = HttpUrl.Builder()
        .scheme("https")
        .host(API_HOST)
        .addPathSegment(path)
        .addQueryParameter("token", token)
        .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
        .build()
        .toString()

    private fun String.validatedKodikPageUrl(token: String): String {
        val parsed = toHttpUrlOrNull()
            ?: throw OnlineSourceException("Kodik вернул некорректную ссылку следующей страницы")
        if (!parsed.isHttps || parsed.host != API_HOST) {
            throw OnlineSourceException("Kodik вернул небезопасную ссылку следующей страницы")
        }
        return parsed.newBuilder().setQueryParameter("token", token).build().toString()
    }

    private companion object {
        const val PUBLIC_TOKEN_TTL_MS = 30 * 60 * 1000L
        const val API_HOST = "kodik-api.com"
        const val PUBLIC_PLAYERS_SCRIPT_URL = "https://kodik-add.com/add-players.min.js?v=2"
    }
}

/**
 * Credentials are tried in order without exposing any token in errors or logs.
 * Only definite auth failures trigger fallback; offline, 429 and geo-blocking do not.
 */
internal class KodikTokenFallback(
    private val personalToken: () -> String?,
    private val bundledToken: () -> String?,
    private val publicToken: suspend (forceRefresh: Boolean) -> String,
) {
    suspend fun <T> execute(request: suspend (String) -> T): T {
        val configured = listOfNotNull(personalToken(), bundledToken())
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        var rejection: Exception? = null

        suspend fun tryCandidate(token: String): T? = try {
            request(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isKodikCredentialError(error)) throw error
            rejection = error
            null
        }

        // The request result is non-null for Kodik's catalog/release operations.
        // Generic nullable results are not used by this provider.
        for (candidate in configured) {
            tryCandidate(candidate)?.let { return it }
        }

        val automatic = try {
            publicToken(false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw OnlineSourceException(
                "Kodik: автоматический доступ недоступен. Проверьте соединение или укажите свой ключ в Настройках.",
                error,
            )
        }
        if (automatic !in configured) {
            tryCandidate(automatic)?.let { return it }
        }

        // Refresh the public token once after a proven rejection. Never loop endlessly.
        val refreshed = try {
            publicToken(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw OnlineSourceException("Kodik: не удалось обновить автоматический токен", error)
        }
        if (refreshed != automatic && refreshed !in configured) {
            tryCandidate(refreshed)?.let { return it }
        }
        throw OnlineSourceException(
            "Kodik отклонил доступные API-токены. Обновите ключ в настройках или повторите позднее.",
            rejection,
        )
    }
}

/** Allow fallback only on unambiguous credential failures, not on 403/429 or a network timeout. */
internal fun isKodikCredentialError(error: Throwable): Boolean {
    val message = error.message?.lowercase(Locale.ROOT).orEmpty()
    if (message.startsWith("kodik вернул ошибку 401")) return true
    val mentionsKey = listOf("token", "api key", "api_key", "apikey", "токен", "ключ доступа")
        .any(message::contains)
    val rejected = listOf(
        "invalid", "incorrect", "wrong", "expired", "unauthorized", "bad token",
        "невер", "недейств", "неправиль", "истёк", "истек", "missing token",
    ).any(message::contains)
    return mentionsKey && rejected
}

internal fun extractKodikPublicToken(script: String): String? =
    Regex("token\\s*[=:]\\s*[\\\"']([0-9a-f]+)[\\\"']", RegexOption.IGNORE_CASE)
        .find(script)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)

internal data class KodikResponseDto(
    val total: Int = 0,
    @SerializedName("next_page") val nextPage: String? = null,
    val results: List<KodikItemDto> = emptyList(),
    val error: String? = null,
)

internal data class KodikItemDto(
    val id: String = "",
    val type: String? = null,
    val link: String? = null,
    val title: String? = null,
    @SerializedName("title_orig") val titleOriginal: String? = null,
    @SerializedName("other_title") val otherTitle: String? = null,
    val translation: KodikTranslationDto? = null,
    val year: Int? = null,
    @SerializedName("last_season") val lastSeason: Int? = null,
    @SerializedName("last_episode") val lastEpisode: Int? = null,
    @SerializedName("episodes_count") val episodesCount: Int? = null,
    @SerializedName("shikimori_id") val shikimoriId: String? = null,
    @SerializedName("kinopoisk_id") val kinopoiskId: String? = null,
    val quality: String? = null,
    @SerializedName("blocked_countries") val blockedCountries: List<String> = emptyList(),
    val seasons: Map<String, KodikSeasonDto> = emptyMap(),
    @SerializedName("material_data") val material: KodikMaterialDto? = null,
)

internal data class KodikTranslationDto(
    val id: Int? = null,
    val title: String? = null,
    val type: String? = null,
)

internal data class KodikSeasonDto(
    val link: String? = null,
    val episodes: Map<String, KodikEpisodeDto> = emptyMap(),
)

internal data class KodikEpisodeDto(
    val link: String? = null,
    val title: String? = null,
    val screenshots: List<String> = emptyList(),
)

internal data class KodikMaterialDto(
    val title: String? = null,
    @SerializedName("anime_title") val animeTitle: String? = null,
    @SerializedName("title_en") val titleEnglish: String? = null,
    @SerializedName("anime_kind") val animeKind: String? = null,
    @SerializedName("anime_status") val animeStatus: String? = null,
    val description: String? = null,
    @SerializedName("anime_description") val animeDescription: String? = null,
    @SerializedName("poster_url") val posterUrl: String? = null,
    @SerializedName("anime_poster_url") val animePosterUrl: String? = null,
    val duration: Double? = null,
    @SerializedName("anime_genres") val animeGenres: List<String> = emptyList(),
    @SerializedName("all_genres") val allGenres: List<String> = emptyList(),
    @SerializedName("episodes_total") val episodesTotal: Int? = null,
    @SerializedName("episodes_aired") val episodesAired: Int? = null,
    val year: Int? = null,
)

internal data class KodikReleaseReference(
    val kind: Kind,
    val value: String,
) {
    enum class Kind(val prefix: String, val queryName: String) {
        SHIKIMORI("shiki", "shikimori_id"),
        KINOPOISK("kp", "kinopoisk_id"),
        KODIK("kodik", "id"),
    }

    val queryName: String get() = kind.queryName
    val queryValue: String get() = value

    override fun toString(): String = "${kind.prefix}:$value"

    companion object {
        fun parse(raw: String): KodikReleaseReference {
            val separator = raw.indexOf(':')
            if (separator <= 0 || separator == raw.lastIndex) {
                throw OnlineSourceException("Некорректный идентификатор релиза Kodik")
            }
            val prefix = raw.substring(0, separator)
            val value = raw.substring(separator + 1)
            val kind = Kind.entries.firstOrNull { it.prefix == prefix }
                ?: throw OnlineSourceException("Неизвестный идентификатор релиза Kodik")
            return KodikReleaseReference(kind, value)
        }
    }
}

internal fun KodikItemDto.releaseReference(): KodikReleaseReference = when {
    !shikimoriId.isNullOrBlank() -> KodikReleaseReference(KodikReleaseReference.Kind.SHIKIMORI, shikimoriId)
    !kinopoiskId.isNullOrBlank() -> KodikReleaseReference(KodikReleaseReference.Kind.KINOPOISK, kinopoiskId)
    else -> KodikReleaseReference(KodikReleaseReference.Kind.KODIK, id)
}

internal fun mergeKodikReleaseVariants(
    reference: KodikReleaseReference,
    responses: List<KodikResponseDto>,
): List<KodikItemDto> {
    val merged = responses.flatMap(KodikResponseDto::results)
    return merged
        .filter { item -> item.matchesReference(reference) }
        .distinctBy(KodikItemDto::variantIdentity)
}

private fun KodikItemDto.matchesReference(reference: KodikReleaseReference): Boolean = when (reference.kind) {
    KodikReleaseReference.Kind.SHIKIMORI -> shikimoriId == reference.value
    KodikReleaseReference.Kind.KINOPOISK -> kinopoiskId == reference.value
    KodikReleaseReference.Kind.KODIK -> id == reference.value
}

private fun KodikItemDto.variantIdentity(): String = buildString {
    append(id)
    append('\u001F').append(translation?.id ?: -1)
    append('\u001F').append(translation?.type.orEmpty())
    append('\u001F').append(link.orEmpty())
}

internal fun List<KodikItemDto>.toReleaseCards(): List<OnlineReleaseCard> =
    groupBy(KodikItemDto::releaseReference)
        .values
        .map { variants ->
            val item = variants.maxByOrNull { it.cardScore() } ?: variants.first()
            val material = item.material
            OnlineReleaseCard(
                providerId = OnlineProviderIds.KODIK,
                providerName = "Kodik",
                id = item.releaseReference().toString(),
                alias = item.shikimoriId ?: item.id,
                name = material?.animeTitle.nonBlank()
                    ?: material?.title.nonBlank()
                    ?: item.title.nonBlank()
                    ?: "Релиз Kodik",
                englishName = material?.titleEnglish.nonBlank() ?: item.titleOriginal.nonBlank(),
                posterUrl = (material?.animePosterUrl.nonBlank() ?: material?.posterUrl.nonBlank()).absoluteKodikUrl(),
                year = material?.year ?: item.year,
                type = material?.animeKind.nonBlank()?.humanAnimeKind() ?: item.type.nonBlank(),
                season = item.lastSeason?.let { "Сезон $it" },
                episodeCount = variants.maxOfOrNull {
                    it.material?.episodesAired ?: it.episodesCount ?: it.lastEpisode ?: 0
                }?.takeIf { it > 0 },
                isOngoing = variants.any { it.material?.animeStatus.equals("ongoing", ignoreCase = true) },
                genres = (material?.animeGenres?.ifEmpty { material.allGenres })
                    .orEmpty()
                    .filter(String::isNotBlank),
                externalIds = ExternalAnimeIds(shikimoriId = item.shikimoriId?.toLongOrNull()),
            )
        }

internal fun List<KodikItemDto>.toReleaseDetails(
    reference: KodikReleaseReference,
): OnlineReleaseDetails {
    val item = maxByOrNull { it.cardScore() } ?: throw OnlineSourceException("Kodik не нашёл релиз")
    val material = item.material
    val seasonNumbers = flatMap { it.seasons.keys }.distinct()
    val episodeBuilders = linkedMapOf<String, KodikEpisodeBuilder>()

    forEach { variant ->
        val streamLabel = variant.translation?.title.nonBlank() ?: "Перевод Kodik"
        val streamTypeLabel = if (variant.translation?.type.equals("subtitles", ignoreCase = true)) {
            "Субтитры"
        } else {
            "Озвучка"
        }
        val quality = QUALITY_REGEX.find(variant.quality.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val durationMs = ((variant.material?.duration ?: material?.duration ?: 0.0) * 60_000.0).toLong()

        if (variant.seasons.isNotEmpty()) {
            variant.seasons.entries.sortedBy { it.key.toDoubleOrNull() ?: Double.MAX_VALUE }.forEach { (season, data) ->
                if (data.episodes.isEmpty()) {
                    data.link.nonBlank()?.let { link ->
                        val id = "${reference}:s$season:player"
                        val builder = episodeBuilders.getOrPut(id) {
                            KodikEpisodeBuilder(
                                id = id,
                                ordinal = null,
                                name = "Сезон $season · выбор серии в плеере",
                                previewUrl = null,
                                durationMs = durationMs,
                                sortOrder = (season.toDoubleOrNull() ?: 1.0) * 10_000.0,
                            )
                        }
                        builder.streams += variant.toStream(link, streamLabel, streamTypeLabel, quality, id)
                    }
                } else {
                    data.episodes.entries.sortedBy { it.key.toDoubleOrNull() ?: Double.MAX_VALUE }
                        .forEach { (episodeNumber, episode) ->
                            episode.link.nonBlank()?.let { link ->
                                val id = "${reference}:s$season:e$episodeNumber"
                                val ordinal = episodeNumber.replace(',', '.').toDoubleOrNull()
                                val seasonPrefix = if (seasonNumbers.size > 1) "Сезон $season" else null
                                val name = listOfNotNull(seasonPrefix, episode.title.nonBlank()).joinToString(" · ")
                                    .ifBlank { seasonPrefix }
                                val builder = episodeBuilders.getOrPut(id) {
                                    KodikEpisodeBuilder(
                                        id = id,
                                        ordinal = ordinal,
                                        name = name,
                                        previewUrl = episode.screenshots.firstOrNull().absoluteKodikUrl(),
                                        durationMs = durationMs,
                                        sortOrder = (season.toDoubleOrNull() ?: 1.0) * 10_000.0 + (ordinal ?: 0.0),
                                    )
                                }
                                builder.streams += variant.toStream(link, streamLabel, streamTypeLabel, quality, id)
                            }
                        }
                }
            }
        } else {
            variant.link.nonBlank()?.let { link ->
                val id = "${reference}:player"
                val serial = variant.type.orEmpty().contains("serial", ignoreCase = true)
                val builder = episodeBuilders.getOrPut(id) {
                    KodikEpisodeBuilder(
                        id = id,
                        ordinal = if (serial) null else 1.0,
                        name = if (serial) "Все серии · выбор в плеере" else "Полный фильм",
                        previewUrl = null,
                        durationMs = durationMs,
                        sortOrder = 1.0,
                    )
                }
                builder.streams += variant.toStream(link, streamLabel, streamTypeLabel, quality, id)
            }
        }
    }

    val episodes = episodeBuilders.values.map { builder ->
        OnlineEpisode(
            providerId = OnlineProviderIds.KODIK,
            id = builder.id,
            releaseId = reference.toString(),
            ordinal = builder.ordinal,
            name = builder.name,
            previewUrl = builder.previewUrl,
            durationMs = builder.durationMs,
            sortOrder = builder.sortOrder,
            streams = builder.streams
                .distinctBy { it.url to it.translation }
                .sortedWith(compareBy<OnlineStream> { it.sourceName == "Субтитры" }.thenBy { it.translation }),
        )
    }.sortedBy { it.sortOrder }

    val voices = mapNotNull { variant ->
        variant.translation?.takeIf { !it.type.equals("subtitles", ignoreCase = true) }?.title.nonBlank()
    }.distinct()
    val subtitles = mapNotNull { variant ->
        variant.translation?.takeIf { it.type.equals("subtitles", ignoreCase = true) }?.title.nonBlank()
    }.distinct()
    val translationSummary = buildList {
        if (voices.isNotEmpty()) add("Озвучек: ${voices.size}")
        if (subtitles.isNotEmpty()) add("Субтитров: ${subtitles.size}")
    }.joinToString(" · ").ifBlank { null }

    return OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK,
        providerName = "Kodik",
        id = reference.toString(),
        alias = item.shikimoriId ?: item.id,
        name = material?.animeTitle.nonBlank() ?: material?.title.nonBlank() ?: item.title.nonBlank() ?: "Релиз Kodik",
        englishName = material?.titleEnglish.nonBlank() ?: item.titleOriginal.nonBlank(),
        posterUrl = (material?.animePosterUrl.nonBlank() ?: material?.posterUrl.nonBlank()).absoluteKodikUrl(),
        year = material?.year ?: item.year,
        type = material?.animeKind.nonBlank()?.humanAnimeKind() ?: item.type.nonBlank(),
        season = item.lastSeason?.let { "Сезон $it" },
        episodeCount = episodes.size,
        description = material?.animeDescription.nonBlank() ?: material?.description.nonBlank(),
        notification = translationSummary,
        genres = (material?.animeGenres?.ifEmpty { material.allGenres }).orEmpty().filter(String::isNotBlank),
        isOngoing = any { it.material?.animeStatus.equals("ongoing", ignoreCase = true) },
        isBlocked = false,
        episodes = episodes,
        externalIds = ExternalAnimeIds(shikimoriId = item.shikimoriId?.toLongOrNull()),
    )
}

private data class KodikEpisodeBuilder(
    val id: String,
    val ordinal: Double?,
    val name: String?,
    val previewUrl: String?,
    val durationMs: Long,
    val sortOrder: Double,
    val streams: MutableList<OnlineStream> = mutableListOf(),
)

private fun KodikItemDto.toStream(
    link: String,
    translationName: String,
    translationType: String,
    quality: Int?,
    episodeId: String,
) = OnlineStream(
    id = "kodik:${id}:${translation?.id ?: translationName}:$episodeId",
    quality = quality,
    url = link.absoluteKodikUrl() ?: link,
    type = OnlineStreamType.EMBED,
    translation = translationName,
    sourceName = translationType,
    translationId = translation?.id?.toString(),
)

private fun KodikItemDto.cardScore(): Int =
    (if (material?.animePosterUrl.nonBlank() != null) 4 else 0) +
        (if (material?.animeDescription.nonBlank() != null) 2 else 0) +
        (if (seasons.isNotEmpty()) 1 else 0)

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotBlank)

private fun String?.absoluteKodikUrl(): String? = nonBlank()?.let { value ->
    when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("//") -> "https:$value"
        else -> "https://$value"
    }
}

private fun String.humanAnimeKind(): String = when (lowercase(Locale.ROOT)) {
    "tv" -> "TV-сериал"
    "movie" -> "Фильм"
    "ova" -> "OVA"
    "ona" -> "ONA"
    "special" -> "Спецвыпуск"
    else -> this
}

private val QUALITY_REGEX = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
