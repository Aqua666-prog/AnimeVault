package com.sergey.animevault.data.aniliberty

import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.online.animeVaultUserAgent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

private const val API_BASE_URL = "https://aniliberty.top/api/v1/"

interface AniLibertyApi {
    @GET("anime/catalog/releases")
    suspend fun getCatalog(
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("f[search]") search: String? = null,
    ): CatalogResponseDto

    @GET("anime/releases/{idOrAlias}")
    suspend fun getRelease(
        @Path("idOrAlias") idOrAlias: String,
    ): ReleaseDto
}

fun createAniLibertyApi(baseClient: OkHttpClient? = null): AniLibertyApi {
    val client = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
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
        .baseUrl(API_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AniLibertyApi::class.java)
}

data class CatalogResponseDto(
    val data: List<ReleaseDto> = emptyList(),
    val meta: MetaDto? = null,
)

data class MetaDto(
    val pagination: PaginationDto? = null,
)

data class PaginationDto(
    @SerializedName("current_page") val currentPage: Int = 1,
    @SerializedName("total_pages") val totalPages: Int = 1,
)

data class ReleaseDto(
    val id: Long = 0,
    val type: ValueDescriptionDto? = null,
    val year: Int? = null,
    val name: ReleaseNameDto? = null,
    val alias: String? = null,
    val season: ValueDescriptionDto? = null,
    val poster: ImageDto? = null,
    @SerializedName("is_ongoing") val isOngoing: Boolean = false,
    val description: String? = null,
    val notification: String? = null,
    @SerializedName("episodes_total") val episodesTotal: Int? = null,
    @SerializedName("is_in_production") val isInProduction: Boolean = false,
    @SerializedName("is_blocked_by_geo") val isBlockedByGeo: Boolean = false,
    @SerializedName("is_blocked_by_copyrights") val isBlockedByCopyrights: Boolean = false,
    val genres: List<GenreDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
)

data class ReleaseNameDto(
    val main: String? = null,
    val english: String? = null,
    val alternative: String? = null,
)

data class ValueDescriptionDto(
    val value: String? = null,
    val description: String? = null,
)

data class GenreDto(
    val id: Long = 0,
    val name: String? = null,
)

data class ImageDto(
    val src: String? = null,
    val preview: String? = null,
    val thumbnail: String? = null,
    val optimized: ImageVariantDto? = null,
)

data class ImageVariantDto(
    val src: String? = null,
    val preview: String? = null,
    val thumbnail: String? = null,
)

data class EpisodeDto(
    val id: String = "",
    val name: String? = null,
    val ordinal: Double? = null,
    val preview: ImageDto? = null,
    @SerializedName("hls_480") val hls480: String? = null,
    @SerializedName("hls_720") val hls720: String? = null,
    @SerializedName("hls_1080") val hls1080: String? = null,
    val duration: Long? = null,
    @SerializedName("sort_order") val sortOrder: Double? = null,
    @SerializedName("release_id") val releaseId: Long = 0,
)
