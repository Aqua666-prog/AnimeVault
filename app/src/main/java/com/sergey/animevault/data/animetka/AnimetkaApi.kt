package com.sergey.animevault.data.animetka

import com.google.gson.JsonElement
import com.sergey.animevault.data.online.animeVaultUserAgent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

internal interface AnimetkaApi {
    @GET("api/anime/main")
    suspend fun main(): JsonElement

    @GET("api/anime/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): JsonElement

    @GET("api/anime/{id}")
    suspend fun details(@Path("id") id: String): JsonElement

    @GET("api/anime/playlist")
    suspend fun playlist(
        @Query("material") materialId: String,
        @Query("tid") translationId: String? = null,
    ): JsonElement

    @GET("api/anime/{id}/trailers")
    suspend fun trailers(@Path("id") id: String): JsonElement
}

internal fun createAnimetkaApi(baseClient: OkHttpClient? = null): AnimetkaApi {
    val client = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", animeVaultUserAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", ANIMETKA_REFERER)
                .build()
            chain.proceed(request)
        }
        .build()
    return Retrofit.Builder()
        .baseUrl(ANIMETKA_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AnimetkaApi::class.java)
}

internal const val ANIMETKA_BASE_URL = "https://animetka.com/"
internal const val ANIMETKA_REFERER = "https://animetka.com/"
internal const val ANIMETKA_ORIGIN = "https://animetka.com"
