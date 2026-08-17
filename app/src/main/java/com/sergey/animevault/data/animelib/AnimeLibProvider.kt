package com.sergey.animevault.data.animelib

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.kodik.KodikStreamResolver
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
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

class AnimeLibProvider internal constructor(
    context: Context,
    private val kodikResolver: KodikStreamResolver = KodikStreamResolver(),
) : TokenOnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIME_LIB,
        name = "AnimeLib",
        description = "Каталог AnimeLib; внешний плеер без токена, прямые потоки с токеном",
        authMode = ProviderAuthMode.OPTIONAL_TOKEN,
        isExperimental = true,
    )

    private val sessions = SecureSessionStore(context)
    private val api = createAnimeLibApi { token() }

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val response = api.getAnime(
            fields = DEFAULT_FIELDS,
            sites = DEFAULT_SITES,
            query = search.trim().takeIf(String::isNotBlank),
            page = page,
        )
        val current = response.meta?.currentPage ?: page
        val nextExists = !response.links?.next.isNullOrBlank()
        return OnlineCatalogPage(
            releases = response.data.map(AnimeLibItemDto::toCard),
            currentPage = current,
            totalPages = if (nextExists) current + 1 else current,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val detail = api.getAnimeDetails(id, DEFAULT_FIELDS).data
        val episodeItems = api.getEpisodes(id).data
        val episodes = episodeItems.map { it.toModel(id) }
            .sortedWith(compareBy<OnlineEpisode> { it.sortOrder ?: Double.MAX_VALUE }.thenBy(OnlineEpisode::id))
        return detail.toDetails(episodes)
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> {
        if (episode.streams.isNotEmpty()) return episode.streams
        val episodeId = episode.sourceRef?.toIntOrNull()
            ?: throw OnlineSourceException("AnimeLib: не удалось определить серию")
        val players = api.getEpisode(episodeId).data.players
        val token = token()
        val result = buildList {
            players.forEach { player ->
                val translation = player.team?.name
                if (player.player.equals("animelib", ignoreCase = true) && !token.isNullOrBlank()) {
                    player.video?.quality.orEmpty().forEach { quality ->
                        add(
                            OnlineStream(
                                id = "animelib:${player.id}:${quality.quality}",
                                quality = quality.quality.takeIf { it > 0 },
                                url = quality.href.toAnimeLibVideoUrl(),
                                type = if (quality.href.contains(".m3u8", ignoreCase = true)) {
                                    OnlineStreamType.HLS
                                } else {
                                    OnlineStreamType.MP4
                                },
                                headers = mapOf(
                                    "Referer" to "https://v3.animelib.org/",
                                    "Authorization" to "Bearer $token",
                                ),
                                translation = translation,
                                sourceName = "AnimeLib",
                            ),
                        )
                    }
                }
                player.src?.takeIf(String::isNotBlank)?.let { src ->
                    add(
                        OnlineStream(
                            id = "${player.player}:${player.id}",
                            quality = null,
                            url = src.absolutePlayerUrl(),
                            type = OnlineStreamType.EMBED,
                            headers = mapOf("Referer" to "https://v3.animelib.org/"),
                            translation = translation,
                            sourceName = player.player?.replaceFirstChar(Char::uppercase),
                        ),
                    )
                }
            }
        }.distinctBy { it.url to it.translation }
        if (result.isEmpty()) {
            throw OnlineSourceException(
                if (token.isNullOrBlank()) {
                    "AnimeLib не отдал доступный плеер. Добавьте токен AnimeLib в настройках."
                } else {
                    "AnimeLib не отдал потоки для этой серии"
                },
            )
        }
        return supervisorScope {
            result.map { stream ->
                async {
                    if (stream.type == OnlineStreamType.EMBED && stream.url.contains("kodik", ignoreCase = true)) {
                        runCatchingCancellable { kodikResolver.resolve(stream) }.getOrDefault(listOf(stream))
                    } else {
                        listOf(stream)
                    }
                }
            }.awaitAll().flatten().distinctBy { listOf(it.url, it.translation, it.quality).joinToString("\u001F") }
        }
    }

    override fun accountState() = ProviderAccountState(
        providerId = OnlineProviderIds.ANIME_LIB,
        isSignedIn = !token().isNullOrBlank(),
        displayName = if (!token().isNullOrBlank()) "Токен сохранён" else null,
    )

    override fun setToken(token: String) {
        sessions.put(TOKEN_KEY, token.trim().removePrefix("Bearer ").trim())
    }

    override fun signOut() {
        sessions.put(TOKEN_KEY, null)
    }

    private fun token(): String? = sessions.get(TOKEN_KEY)

    private companion object {
        const val TOKEN_KEY = "animelib.token"
        val DEFAULT_FIELDS = listOf("rate", "rate_avg", "releaseDate")
        val DEFAULT_SITES = listOf(1, 3)
    }
}

internal interface AnimeLibApi {
    @GET("anime")
    suspend fun getAnime(
        @Query("fields[]") fields: List<String>,
        @Query("site_id[]") sites: List<Int>,
        @Query("q") query: String?,
        @Query("page") page: Int,
    ): AnimeLibListResponseDto

    @GET("anime/{slugUrl}")
    suspend fun getAnimeDetails(
        @Path("slugUrl") slugUrl: String,
        @Query("fields[]") fields: List<String>,
    ): AnimeLibDetailResponseDto

    @GET("episodes")
    suspend fun getEpisodes(
        @Query("anime_id") animeId: String,
    ): AnimeLibEpisodeListResponseDto

    @GET("episodes/{id}")
    suspend fun getEpisode(
        @Path("id") id: Int,
    ): AnimeLibEpisodeDetailResponseDto
}

internal fun createAnimeLibApi(token: () -> String?): AnimeLibApi {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", ANIME_LIB_BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
                .header("Origin", "https://v3.animelib.org")
                .header("Referer", "https://v3.animelib.org/")
                .apply { token()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
                .build()
            chain.proceed(request)
        }
        .build()
    return Retrofit.Builder()
        .baseUrl("https://api.cdnlibs.org/api/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AnimeLibApi::class.java)
}

private const val ANIME_LIB_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

internal data class AnimeLibListResponseDto(
    val data: List<AnimeLibItemDto> = emptyList(),
    val links: AnimeLibLinksDto? = null,
    val meta: AnimeLibMetaDto? = null,
)

internal data class AnimeLibLinksDto(
    val next: String? = null,
)

internal data class AnimeLibMetaDto(
    @SerializedName("current_page") val currentPage: Int = 1,
)

internal data class AnimeLibDetailResponseDto(
    val data: AnimeLibDetailDto,
)

internal data class AnimeLibEpisodeListResponseDto(
    val data: List<AnimeLibEpisodeDto> = emptyList(),
)

internal data class AnimeLibEpisodeDetailResponseDto(
    val data: AnimeLibEpisodeDetailDto,
)

internal open class AnimeLibItemDto(
    val id: Int = 0,
    val name: String? = null,
    @SerializedName("rus_name") val russianName: String? = null,
    @SerializedName("eng_name") val englishName: String? = null,
    @SerializedName("slug_url") val slugUrl: String = "",
    val cover: AnimeLibCoverDto? = null,
    @SerializedName("type") val animeType: AnimeLibLabelDto? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    val status: AnimeLibLabelDto? = null,
    val genres: List<AnimeLibGenreDto> = emptyList(),
)

internal data class AnimeLibDetailDto(
    val id: Int = 0,
    val name: String? = null,
    @SerializedName("rus_name") val russianName: String? = null,
    @SerializedName("eng_name") val englishName: String? = null,
    @SerializedName("slug_url") val slugUrl: String = "",
    val cover: AnimeLibCoverDto? = null,
    @SerializedName("type") val animeType: AnimeLibLabelDto? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    val status: AnimeLibLabelDto? = null,
    val summary: String? = null,
    val genres: List<AnimeLibGenreDto> = emptyList(),
)

internal data class AnimeLibCoverDto(
    val thumbnail: String? = null,
    val default: String? = null,
    val md: String? = null,
)

internal data class AnimeLibLabelDto(
    val label: String? = null,
)

internal data class AnimeLibGenreDto(
    val name: String? = null,
)

internal data class AnimeLibEpisodeDto(
    val id: Int = 0,
    val name: String? = null,
    val number: String? = null,
    val season: String? = null,
    @SerializedName("item_number") val itemNumber: Int? = null,
)

internal data class AnimeLibEpisodeDetailDto(
    val players: List<AnimeLibPlayerDto> = emptyList(),
)

internal data class AnimeLibPlayerDto(
    val id: Int = 0,
    val player: String? = null,
    val team: AnimeLibTeamDto? = null,
    val src: String? = null,
    val video: AnimeLibVideoDto? = null,
)

internal data class AnimeLibTeamDto(
    val name: String? = null,
)

internal data class AnimeLibVideoDto(
    val quality: List<AnimeLibQualityDto> = emptyList(),
)

internal data class AnimeLibQualityDto(
    val href: String = "",
    val quality: Int = 0,
)

private fun AnimeLibItemDto.toCard() = OnlineReleaseCard(
    providerId = OnlineProviderIds.ANIME_LIB,
    providerName = "AnimeLib",
    id = slugUrl.ifBlank { id.toString() },
    alias = slugUrl,
    name = russianName?.takeIf(String::isNotBlank) ?: name?.takeIf(String::isNotBlank) ?: "Релиз #$id",
    englishName = englishName?.takeIf(String::isNotBlank),
    posterUrl = cover.bestUrl(),
    year = releaseDate.findYear(),
    type = animeType?.label,
    season = null,
    episodeCount = null,
    isOngoing = status?.label.orEmpty().contains("выходит", ignoreCase = true),
    genres = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) },
)

private fun AnimeLibDetailDto.toDetails(episodes: List<OnlineEpisode>) = OnlineReleaseDetails(
    providerId = OnlineProviderIds.ANIME_LIB,
    providerName = "AnimeLib",
    id = slugUrl.ifBlank { id.toString() },
    alias = slugUrl,
    name = russianName?.takeIf(String::isNotBlank) ?: name?.takeIf(String::isNotBlank) ?: "Релиз #$id",
    englishName = englishName?.takeIf(String::isNotBlank),
    posterUrl = cover.bestUrl(),
    year = releaseDate.findYear(),
    type = animeType?.label,
    season = null,
    episodeCount = episodes.size,
    description = summary?.takeIf(String::isNotBlank),
    notification = null,
    genres = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) },
    isOngoing = status?.label.orEmpty().contains("выходит", ignoreCase = true),
    isBlocked = false,
    episodes = episodes,
)

private fun AnimeLibEpisodeDto.toModel(releaseId: String): OnlineEpisode {
    val ordinal = number?.replace(',', '.')?.toDoubleOrNull() ?: itemNumber?.toDouble()
    return OnlineEpisode(
        providerId = OnlineProviderIds.ANIME_LIB,
        id = id.toString(),
        releaseId = releaseId,
        ordinal = ordinal,
        name = name?.takeIf(String::isNotBlank),
        previewUrl = null,
        durationMs = 0L,
        sortOrder = ordinal,
        streams = emptyList(),
        sourceRef = id.toString(),
    )
}

private fun AnimeLibCoverDto?.bestUrl(): String? = listOfNotNull(
    this?.md,
    this?.default,
    this?.thumbnail,
).firstOrNull(String::isNotBlank)?.absoluteCoverUrl()

private fun String.absoluteCoverUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> "https://cover.imglib.info$this"
    else -> "https://cover.imglib.info/$this"
}

private fun String?.findYear(): Int? = this?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

private fun String.toAnimeLibVideoUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> "https://video1.cdnlibs.org$this"
    else -> "https://video1.cdnlibs.org/.%D0%B0s/$this"
}

private fun String.absolutePlayerUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> "https://v3.animelib.org$this"
    else -> "https://v3.animelib.org/$this"
}
