package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test

class TenraiStaleCacheTest {
    @Test
    fun serverFailure_usesPersistedResponseAfterRetriesExhausted() = runTest {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime/1/full") { exchange ->
            if (hits.incrementAndGet() == 1) {
                val body = "{\"data\":{\"mal_id\":1,\"title\":\"Cached title\"}}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val cache = MemoryTenraiCache()
            val client = TenraiClient(
                baseClient = OkHttpClient(),
                baseUrl = "http://127.0.0.1:${server.address.port}/v1/".toHttpUrl(),
                retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                limiter = TenraiRateLimiter(minSpacingMs = 0),
                responseCache = cache,
            )

            val fresh = client.getResult("anime/1/full")
            val stale = client.getResult("anime/1/full")

            assertThat(fresh.origin).isEqualTo(TenraiResponseOrigin.NETWORK)
            assertThat(stale.origin).isEqualTo(TenraiResponseOrigin.STALE_CACHE)
            assertThat(stale.body).contains("Cached title")
            assertThat(client.healthSnapshot().servingStaleCache).isTrue()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clientError_doesNotMask404WithOldCache() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime/404/full") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val cache = MemoryTenraiCache()
            cache.put("anime/404/full", "{\"data\":{\"mal_id\":404,\"title\":\"Old\"}}")
            val client = TenraiClient(
                baseClient = OkHttpClient(),
                baseUrl = "http://127.0.0.1:${server.address.port}/v1/".toHttpUrl(),
                retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                limiter = TenraiRateLimiter(minSpacingMs = 0),
                responseCache = cache,
            )

            val error = runCatching { client.getResult("anime/404/full") }.exceptionOrNull()
            assertThat(error).isInstanceOf(TenraiHttpException::class.java)
            assertThat((error as TenraiHttpException).code).isEqualTo(404)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun queryParameters_haveIndependentCacheKeys() = runTest {
        val cache = MemoryTenraiCache()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val body = "{\"query\":\"$query\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = TenraiClient(
                baseClient = OkHttpClient(),
                baseUrl = "http://127.0.0.1:${server.address.port}/v1/".toHttpUrl(),
                retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                limiter = TenraiRateLimiter(minSpacingMs = 0),
                responseCache = cache,
            )
            client.get("anime", mapOf("q" to "Frieren", "page" to "1"))
            client.get("anime", mapOf("q" to "Frieren", "page" to "2"))
            assertThat(cache.keys).hasSize(2)
        } finally {
            server.stop(0)
        }
    }
}

private class MemoryTenraiCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TenraiResponseCache {
    private val values = linkedMapOf<String, CachedTenraiResponse>()
    val keys: Set<String> get() = values.keys

    override suspend fun get(key: String): CachedTenraiResponse? = values[key]

    override suspend fun put(key: String, body: String) {
        values[key] = CachedTenraiResponse(body = body, storedAtMs = nowMs())
    }

    override suspend fun clear() {
        values.clear()
    }
}
