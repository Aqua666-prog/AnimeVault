package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test

class TenraiMetadataFallbackTest {
    @Test
    fun overview_usesFallbackWhenTenraiIsUnavailableAndNoStaleCacheExists() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime/52991/full") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val fallback = object : TenraiMetadataFallback {
                override suspend fun findOverviewByMalId(malId: Long): TenraiAnimeOverview? = fallbackOverview(malId)
            }
            val repository = TenraiMetadataRepository(
                client = TenraiClient(
                    baseClient = OkHttpClient(),
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1/".toHttpUrl(),
                    retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                    limiter = TenraiRateLimiter(minSpacingMs = 0),
                ),
                fallback = fallback,
            )

            val result = repository.getOverview(52991)
            assertThat(result.malId).isEqualTo(52991)
            assertThat(result.title).isEqualTo("Fallback Frieren")
            assertThat(result.metadataSource).isEqualTo("Test fallback")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun search_usesFallbackOnlyAfterTenraiFailure() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val fallback = object : TenraiMetadataFallback {
                override suspend fun findOverviewByMalId(malId: Long): TenraiAnimeOverview? = null

                override suspend fun searchAnime(query: String, page: Int, sfw: Boolean): TenraiPage<TenraiCatalogItem> =
                    TenraiPage(
                        items = listOf(
                            TenraiCatalogItem(
                                malId = 52991,
                                title = "Fallback Frieren",
                                englishTitle = null,
                                japaneseTitle = null,
                                imageUrl = null,
                                type = "TV",
                                episodeCount = 28,
                                status = "FINISHED",
                                score = 8.9,
                                year = 2023,
                                season = null,
                                broadcast = null,
                            ),
                        ),
                        page = 1,
                        lastPage = 1,
                        hasNextPage = false,
                    )
            }
            val repository = TenraiMetadataRepository(
                client = TenraiClient(
                    baseClient = OkHttpClient(),
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1/".toHttpUrl(),
                    retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                    limiter = TenraiRateLimiter(minSpacingMs = 0),
                ),
                fallback = fallback,
            )

            val result = repository.searchAnime("Frieren")
            assertThat(result.items).hasSize(1)
            assertThat(result.items.single().malId).isEqualTo(52991)
        } finally {
            server.stop(0)
        }
    }
}

private fun fallbackOverview(malId: Long): TenraiAnimeOverview = TenraiAnimeOverview(
    malId = malId,
    title = "Fallback Frieren",
    englishTitle = null,
    japaneseTitle = null,
    posterUrl = null,
    largePosterUrl = null,
    type = "TV",
    source = null,
    episodeCount = 28,
    status = "FINISHED",
    airing = false,
    duration = null,
    rating = null,
    score = 8.9,
    scoredBy = null,
    rank = null,
    popularity = null,
    members = null,
    favorites = null,
    synopsis = null,
    background = null,
    season = null,
    year = 2023,
    broadcast = null,
    studios = emptyList(),
    genres = listOf("Fantasy"),
    relations = emptyList(),
    openings = emptyList(),
    endings = emptyList(),
    externalLinks = emptyList(),
    streamingLinks = emptyList(),
    trailer = null,
    metadataSource = "Test fallback",
    isStale = false,
)
