package com.sergey.animevault.data.animevost

import com.google.gson.annotations.SerializedName
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
import com.sergey.animevault.data.online.animeVaultUserAgent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

class AnimeVostProvider internal constructor(
    private val api: AnimeVostApi = createAnimeVostApi(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIME_VOST,
        name = "AnimeVost",
        description = "Каталог AnimeVost и прямые MP4-потоки",
    )

    private val releaseCache = ConcurrentHashMap<String, AnimeVostItemDto>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val response = if (search.isBlank()) {
            api.getLatest(page = page, quantity = limit)
        } else {
            if (page > 1) return OnlineCatalogPage(emptyList(), page, page)
            api.search(search.trim())
        }
        if (!response.error.isNullOrBlank()) throw OnlineSourceException(response.error)
        response.data.forEach { releaseCache[it.id.toString()] = it }
        val count = response.state?.count ?: response.data.size
        val totalPages = if (search.isNotBlank()) {
            1
        } else {
            max(page, ceil(count.toDouble() / limit.coerceAtLeast(1)).toInt())
                .let { known -> if (response.data.size >= limit && known <= page) page + 1 else known }
        }
        return OnlineCatalogPage(
            releases = response.data.map(AnimeVostItemDto::toCard),
            currentPage = response.state?.page ?: page,
            totalPages = totalPages,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val item = releaseCache[id] ?: api.getLatest(page = 1, quantity = 100)
            .data
            .firstOrNull { it.id.toString() == id }
            ?.also { releaseCache[id] = it }
            ?: throw OnlineSourceException("AnimeVost: релиз $id не найден. Вернитесь в каталог и откройте его снова.")
        val playlist = api.getPlaylist(item.id)
        if (!playlist.error.isNullOrBlank()) throw OnlineSourceException(playlist.error)
        val episodes = playlist.items.mapIndexed { index, episode -> episode.toModel(item.id, index) }
        return item.toDetails(episodes)
    }
}

internal interface AnimeVostApi {
    @GET("last")
    suspend fun getLatest(
        @Query("page") page: Int,
        @Query("quantity") quantity: Int,
    ): AnimeVostListResponseDto

    @FormUrlEncoded
    @POST("search")
    suspend fun search(@Field("name") name: String): AnimeVostListResponseDto

    @FormUrlEncoded
    @POST("playlist")
    suspend fun getPlaylist(@Field("id") id: Int): AnimeVostPlaylistEnvelope
}

internal fun createAnimeVostApi(): AnimeVostApi {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", animeVaultUserAgent())
                    .header("Accept", "application/json")
                    .build(),
            )
        }
        .build()
    return Retrofit.Builder()
        .baseUrl("https://api.animevost.org/v1/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AnimeVostApi::class.java)
}

internal data class AnimeVostListResponseDto(
    val state: AnimeVostStateDto? = null,
    val data: List<AnimeVostItemDto> = emptyList(),
    val error: String? = null,
)

internal data class AnimeVostStateDto(
    val page: Int = 1,
    val count: Int = 0,
)

internal data class AnimeVostItemDto(
    val id: Int = 0,
    val title: String = "",
    val description: String? = null,
    val genre: String? = null,
    val year: String? = null,
    @SerializedName("urlImagePreview") val poster: String? = null,
    @SerializedName("screenImage") val screenshots: List<String> = emptyList(),
    val type: String? = null,
    val series: String? = null,
)

/** AnimeVost normally returns a top-level array; this adapter also accepts its error envelope. */
internal class AnimeVostPlaylistEnvelope : ArrayList<AnimeVostPlaylistItemDto>() {
    val items: List<AnimeVostPlaylistItemDto> get() = this
    val error: String? = null
}

internal data class AnimeVostPlaylistItemDto(
    val name: String? = null,
    val hd: String? = null,
    val std: String? = null,
    val preview: String? = null,
)

private fun AnimeVostItemDto.toCard() = OnlineReleaseCard(
    providerId = OnlineProviderIds.ANIME_VOST,
    providerName = "AnimeVost",
    id = id.toString(),
    alias = id.toString(),
    name = title.ifBlank { "Релиз #$id" },
    englishName = null,
    posterUrl = poster?.asHttps(),
    year = year?.findFourDigitYear(),
    type = type,
    season = null,
    episodeCount = series?.episodeCount(),
    isOngoing = type.orEmpty().contains("онгоинг", ignoreCase = true),
    genres = genre.orEmpty().split(',', '/').map(String::trim).filter(String::isNotBlank),
)

private fun AnimeVostItemDto.toDetails(episodes: List<OnlineEpisode>) = OnlineReleaseDetails(
    providerId = OnlineProviderIds.ANIME_VOST,
    providerName = "AnimeVost",
    id = id.toString(),
    alias = id.toString(),
    name = title.ifBlank { "Релиз #$id" },
    englishName = null,
    posterUrl = poster?.asHttps(),
    year = year?.findFourDigitYear(),
    type = type,
    season = null,
    episodeCount = episodes.size.takeIf { it > 0 } ?: series?.episodeCount(),
    description = description?.takeIf(String::isNotBlank),
    notification = null,
    genres = genre.orEmpty().split(',', '/').map(String::trim).filter(String::isNotBlank),
    isOngoing = type.orEmpty().contains("онгоинг", ignoreCase = true),
    isBlocked = false,
    episodes = episodes,
)

private fun AnimeVostPlaylistItemDto.toModel(releaseId: Int, index: Int): OnlineEpisode {
    val ordinal = name.orEmpty().findFirstNumber() ?: (index + 1).toDouble()
    val streams = listOfNotNull(
        hd?.toMp4Stream(720),
        std?.toMp4Stream(480),
    ).distinctBy(OnlineStream::url)
    return OnlineEpisode(
        providerId = OnlineProviderIds.ANIME_VOST,
        id = "$releaseId:$ordinal",
        releaseId = releaseId.toString(),
        ordinal = ordinal,
        name = name?.takeIf(String::isNotBlank),
        previewUrl = preview?.asHttps(),
        durationMs = 0L,
        sortOrder = ordinal,
        streams = streams,
    )
}

private fun String.toMp4Stream(quality: Int): OnlineStream? = takeIf(String::isNotBlank)?.let { url ->
    OnlineStream(
        id = "${quality}p",
        quality = quality,
        url = url.asHttps(),
        type = OnlineStreamType.MP4,
        headers = mapOf("Referer" to "https://animevost.org/"),
    )
}

private fun String.asHttps(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://${removePrefix("http://")}"
    else -> this
}

private fun String.findFourDigitYear(): Int? = Regex("(?:19|20)\\d{2}").find(this)?.value?.toIntOrNull()

private fun String.findFirstNumber(): Double? = Regex("\\d+(?:[.,]\\d+)?")
    .find(this)
    ?.value
    ?.replace(',', '.')
    ?.toDoubleOrNull()

private fun String.episodeCount(): Int? = Regex("\\d+").findAll(this)
    .mapNotNull { it.value.toIntOrNull() }
    .maxOrNull()
