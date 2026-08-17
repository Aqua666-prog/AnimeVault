package com.sergey.animevault.data.yummy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.kodik.KodikStreamResolver
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
import com.sergey.animevault.data.online.SecureSessionStore
import com.sergey.animevault.data.online.TokenOnlineProvider
import com.sergey.animevault.data.online.absoluteUrl
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.util.runCatchingCancellable
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YummyAnime / Yani API adapter.
 *
 * The current public Yani API works without an application token. A user token
 * remains optional for compatibility and is stored through Android Keystore.
 *
 * Video entries are kept as EMBED streams. Kodik embeds are upgraded to native
 * HLS through the existing resolver; other players stay available through the
 * player's WebView fallback instead of relying on brittle player-specific hacks.
 */
class YummyAnimeProvider internal constructor(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val gson: Gson = Gson(),
    private val kodikResolver: KodikStreamResolver = KodikStreamResolver(),
) : TokenOnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.YUMMY,
        name = "YummyAnime",
        description = "Большой каталог YummyAnime с вариантами озвучки из публичного Yani API",
        authMode = ProviderAuthMode.OPTIONAL_TOKEN,
        isExperimental = true,
        searchHint = "Название аниме в YummyAnime",
    )

    private val sessions = SecureSessionStore(context)

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val offset = (safePage - 1) * safeLimit
        val url = "$API_BASE/anime".toHttpUrl().newBuilder().apply {
            search.trim().takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
            addQueryParameter("limit", safeLimit.toString())
            addQueryParameter("offset", offset.toString())
            addQueryParameter("sort", "rating")
            addQueryParameter("sort_forward", "false")
        }.build()
        val response = requestJson(url.toString(), YummySearchResponseDto::class.java)
        val releases = response.response.orEmpty().mapNotNull(YummySearchItemDto::toCard)
        val hasNext = releases.size >= safeLimit
        return OnlineCatalogPage(
            releases = releases,
            currentPage = safePage,
            totalPages = if (hasNext) safePage + 1 else safePage,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val animeId = id.toIntOrNull()?.takeIf { it > 0 }
            ?: throw OnlineSourceException("YummyAnime: некорректный ID тайтла")
        val details = requestJson("$API_BASE/anime/$animeId", YummyDetailsEnvelopeDto::class.java).response
            ?: throw OnlineSourceException("YummyAnime не вернул карточку тайтла")
        val videos = requestJson("$API_BASE/anime/$animeId/videos", YummyVideosEnvelopeDto::class.java)
            .response.orEmpty()
        val episodes = mergeYummyEpisodes(animeId, videos)
        val status = details.animeStatus?.alias.orEmpty().lowercase(Locale.ROOT)
        val title = details.title.trim().ifBlank { "YummyAnime #$animeId" }
        return OnlineReleaseDetails(
            providerId = OnlineProviderIds.YUMMY,
            providerName = descriptor.name,
            id = animeId.toString(),
            alias = details.animeUrl?.trim().takeUnless { it.isNullOrBlank() } ?: animeId.toString(),
            name = title,
            englishName = details.otherTitles.orEmpty()
                .firstOrNull { candidate -> candidate.isNotBlank() && !candidate.equals(title, ignoreCase = true) },
            posterUrl = details.poster.bestUrl(),
            year = details.year,
            type = details.type?.name?.takeIf(String::isNotBlank),
            season = null,
            episodeCount = details.episodes?.count ?: episodes.size.takeIf { it > 0 },
            description = details.description?.trim()?.takeIf(String::isNotBlank),
            notification = buildYummyNotification(videos),
            genres = details.genres.orEmpty().mapNotNull { it.title?.trim()?.takeIf(String::isNotBlank) },
            isOngoing = status.contains("ongo") || status.contains("air"),
            isBlocked = false,
            episodes = episodes,
            externalIds = ExternalAnimeIds(
                shikimoriId = details.remoteIds?.shikimoriId?.toLong(),
                malId = details.remoteIds?.myAnimeListId?.toLong(),
            ),
        )
    }

    override suspend fun resolveStreams(releaseId: String, episode: OnlineEpisode): List<OnlineStream> =
        supervisorScope {
            episode.streams.map { stream ->
                async {
                    if (stream.type == OnlineStreamType.EMBED && stream.url.isKodikEmbed()) {
                        runCatchingCancellable { kodikResolver.resolve(stream) }.getOrDefault(listOf(stream))
                    } else {
                        listOf(stream)
                    }
                }
            }.awaitAll().flatten().distinctBy { listOf(it.url, it.translation, it.sourceName, it.quality).joinToString("\u001F") }
        }

    override fun accountState() = ProviderAccountState(
        providerId = OnlineProviderIds.YUMMY,
        isSignedIn = !token().isNullOrBlank(),
        displayName = if (!token().isNullOrBlank()) "Application token сохранён" else null,
    )

    override fun setToken(token: String) {
        sessions.put(TOKEN_KEY, token.trim())
    }

    override fun signOut() {
        sessions.put(TOKEN_KEY, null)
    }

    private suspend fun <T> requestJson(url: String, type: Class<T>): T {
        val token = token()?.takeIf(String::isNotBlank)
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .get()
                .onlineHeaders(referer = SITE_BASE)
                .apply { token?.let { header("X-Application", it) } }
                .header("Lang", "ru")
                .build(),
            descriptor.name,
        )
        return runCatching { gson.fromJson(body, type) }
            .getOrElse { throw OnlineSourceException("YummyAnime вернул повреждённый JSON", it) }
    }

    private fun token(): String? = sessions.get(TOKEN_KEY)

    private companion object {
        const val API_BASE = "https://api.yani.tv"
        const val SITE_BASE = "https://www.yummy-anime.ru/"
        const val TOKEN_KEY = "yummy.application.token"
        const val MAX_PAGE_SIZE = 100
    }
}

internal data class YummySearchResponseDto(
    val response: List<YummySearchItemDto>? = emptyList(),
)

internal data class YummySearchItemDto(
    @SerializedName("anime_id") val animeId: Int? = null,
    val title: String? = null,
    val poster: YummyPosterDto? = null,
    val year: Int? = null,
    val type: YummyTypeDto? = null,
    val episodes: YummyEpisodesDto? = null,
    @SerializedName("anime_status") val animeStatus: YummyAliasDto? = null,
    @SerializedName("remote_ids") val remoteIds: YummyRemoteIdsDto? = null,
) {
    fun toCard(): OnlineReleaseCard? {
        val id = animeId?.takeIf { it > 0 } ?: return null
        val cleanTitle = title?.trim()?.takeIf(String::isNotBlank) ?: return null
        return OnlineReleaseCard(
            providerId = OnlineProviderIds.YUMMY,
            providerName = "YummyAnime",
            id = id.toString(),
            alias = id.toString(),
            name = cleanTitle,
            englishName = null,
            posterUrl = poster.bestUrl(),
            year = year,
            type = type?.name?.takeIf(String::isNotBlank),
            season = null,
            episodeCount = episodes?.count,
            isOngoing = animeStatus?.alias.orEmpty().let { status ->
                status.contains("ongo", ignoreCase = true) || status.contains("air", ignoreCase = true)
            },
            externalIds = ExternalAnimeIds(
                shikimoriId = remoteIds?.shikimoriId?.toLong(),
                malId = remoteIds?.myAnimeListId?.toLong(),
            ),
        )
    }
}

internal data class YummyDetailsEnvelopeDto(
    val response: YummyDetailsDto? = null,
)

internal data class YummyDetailsDto(
    @SerializedName("anime_id") val animeId: Int? = null,
    @SerializedName("anime_url") val animeUrl: String? = null,
    val title: String = "",
    val description: String? = null,
    val poster: YummyPosterDto? = null,
    val genres: List<YummyNamedDto>? = emptyList(),
    val year: Int? = null,
    @SerializedName("anime_status") val animeStatus: YummyAliasDto? = null,
    val type: YummyTypeDto? = null,
    val episodes: YummyEpisodesDto? = null,
    @SerializedName("other_titles") val otherTitles: List<String>? = emptyList(),
    @SerializedName("remote_ids") val remoteIds: YummyRemoteIdsDto? = null,
)

internal data class YummyPosterDto(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val fullsize: String? = null,
    val mega: String? = null,
)

internal data class YummyNamedDto(
    val id: Int? = null,
    val title: String? = null,
)

internal data class YummyAliasDto(
    val title: String? = null,
    val alias: String? = null,
)

internal data class YummyTypeDto(
    val name: String? = null,
    val alias: String? = null,
)

internal data class YummyEpisodesDto(
    val count: Int? = null,
    val aired: Int? = null,
)

internal data class YummyRemoteIdsDto(
    @SerializedName("myanimelist_id") val myAnimeListId: Int? = null,
    @SerializedName("shikimori_id") val shikimoriId: Int? = null,
)

internal data class YummyVideosEnvelopeDto(
    val response: List<YummyVideoDto>? = emptyList(),
)

internal data class YummyVideoDto(
    @SerializedName("video_id") val videoId: Int = 0,
    val data: YummyVideoDataDto? = null,
    val number: String? = null,
    @SerializedName("iframe_url") val iframeUrl: String? = null,
    val duration: Int? = null,
)

internal data class YummyVideoDataDto(
    val player: String? = null,
    val dubbing: String? = null,
    @SerializedName("player_id") val playerId: Int? = null,
)

internal fun mergeYummyEpisodes(animeId: Int, videos: List<YummyVideoDto>): List<OnlineEpisode> {
    data class IndexedVideo(val item: YummyVideoDto, val fallbackOrdinal: Int)
    val groups = linkedMapOf<String, MutableList<IndexedVideo>>()
    videos.forEachIndexed { index, video ->
        val number = video.number?.trim()?.takeIf(String::isNotBlank) ?: (index + 1).toString()
        groups.getOrPut(number) { mutableListOf() } += IndexedVideo(video, index + 1)
    }
    return groups.entries.mapIndexed { groupIndex, (number, variants) ->
        val ordinal = parseYummyOrdinal(number) ?: (groupIndex + 1).toDouble()
        val streams = variants.mapNotNull { indexed ->
            val video = indexed.item
            val rawUrl = video.iframeUrl?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = rawUrl.absoluteUrl("https://www.yummy-anime.ru")
            OnlineStream(
                id = "yummy:$animeId:${video.videoId.takeIf { it > 0 } ?: indexed.fallbackOrdinal}",
                quality = null,
                url = url,
                type = if (url.contains(".m3u8", ignoreCase = true)) OnlineStreamType.HLS else OnlineStreamType.EMBED,
                headers = mapOf("Referer" to "https://www.yummy-anime.ru/"),
                translation = video.data?.dubbing?.trim()?.takeIf(String::isNotBlank),
                sourceName = video.data?.player?.trim()?.takeIf(String::isNotBlank) ?: "YummyAnime",
            )
        }.distinctBy { it.url to it.translation }
        val durationMs = variants.mapNotNull { it.item.duration }.maxOrNull()?.toLong()?.times(1_000L) ?: 0L
        OnlineEpisode(
            providerId = OnlineProviderIds.YUMMY,
            id = "yummy:$animeId:$number",
            releaseId = animeId.toString(),
            ordinal = ordinal,
            name = if (number.toDoubleOrNull() != null) "$number серия" else number,
            previewUrl = null,
            durationMs = durationMs,
            sortOrder = ordinal,
            streams = streams,
        )
    }.filter { it.streams.isNotEmpty() }
        .sortedWith(compareBy<OnlineEpisode> { it.sortOrder ?: Double.MAX_VALUE }.thenBy(OnlineEpisode::id))
}

private fun parseYummyOrdinal(value: String): Double? = value
    .replace(',', '.')
    .trim()
    .toDoubleOrNull()

private fun YummyPosterDto?.bestUrl(): String? = listOfNotNull(
    this?.mega,
    this?.fullsize,
    this?.big,
    this?.medium,
    this?.small,
).firstOrNull { it.isNotBlank() }?.absoluteUrl("https://www.yummy-anime.ru")

private fun buildYummyNotification(videos: List<YummyVideoDto>): String? {
    val dubbings = videos.mapNotNull { it.data?.dubbing?.trim()?.takeIf(String::isNotBlank) }.distinct()
    val players = videos.mapNotNull { it.data?.player?.trim()?.takeIf(String::isNotBlank) }.distinct()
    if (dubbings.isEmpty() && players.isEmpty()) return null
    return buildList {
        if (dubbings.isNotEmpty()) add("озвучек: ${dubbings.size}")
        if (players.isNotEmpty()) add("плееров: ${players.size}")
    }.joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
}

private fun String.isKodikEmbed(): Boolean = contains("kodik", ignoreCase = true)
