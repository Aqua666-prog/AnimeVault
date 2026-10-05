package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test

class TenraiClientTest {
    @Test
    fun retriesTransient503_thenReturnsBody() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        server.createContext("/v1/anime/1/videos") { exchange ->
            if (hits.incrementAndGet() < 3) {
                write(exchange, 503, "temporarily unavailable")
            } else {
                write(exchange, 200, "{\"data\":{}}")
            }
        }
        server.start()
        try {
            val client = testClient(server)
            val body = client.get("anime/1/videos")

            assertThat(body).isEqualTo("{\"data\":{}}")
            assertThat(hits.get()).isEqualTo(3)
            assertThat(client.healthSnapshot().state).isEqualTo(TenraiHealthState.HEALTHY)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rateLimit429_honorsRetryAfterBeforeRetry() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        val delays = mutableListOf<Long>()
        server.createContext("/v1/anime/2/videos") { exchange ->
            if (hits.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "1")
                write(exchange, 429, "slow down")
            } else {
                write(exchange, 200, "{\"data\":{}}")
            }
        }
        server.start()
        try {
            val client = testClient(server, sleeper = { delays += it })
            client.get("anime/2/videos")

            assertThat(hits.get()).isEqualTo(2)
            assertThat(delays).containsExactly(1_000L)
            assertThat(client.healthSnapshot().state).isEqualTo(TenraiHealthState.HEALTHY)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clientError404_isNotRetried() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        server.createContext("/v1/anime/404/videos") { exchange ->
            hits.incrementAndGet()
            write(exchange, 404, "missing")
        }
        server.start()
        try {
            val client = testClient(server)
            val error = runCatching { client.get("anime/404/videos") }.exceptionOrNull()

            assertThat(error).isInstanceOf(TenraiHttpException::class.java)
            assertThat((error as TenraiHttpException).code).isEqualTo(404)
            assertThat(hits.get()).isEqualTo(1)
            assertThat(client.healthSnapshot().state).isEqualTo(TenraiHealthState.HEALTHY)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun emptySuccessfulBody_isProtocolFailure() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/anime/3/videos") { exchange ->
            write(exchange, 200, "")
        }
        server.start()
        try {
            val client = testClient(server)
            val error = runCatching { client.get("anime/3/videos") }.exceptionOrNull()

            assertThat(error).isInstanceOf(TenraiProtocolException::class.java)
            assertThat(client.healthSnapshot().state).isEqualTo(TenraiHealthState.UNAVAILABLE)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun limiter_throttlesFourthRequestInsideOneSecond() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = TenraiRateLimiter(
            maxPerSecond = 3,
            maxPerMinute = 60,
            minSpacingMs = 0L,
            nowMs = { now },
            sleeper = { wait ->
                waits += wait
                now += wait
            },
        )

        repeat(4) { limiter.acquire() }

        assertThat(waits).containsExactly(1_001L)
    }

    @Test
    fun retryAfterParser_supportsSeconds() {
        assertThat(parseRetryAfterMillis("7", nowMs = 0L)).isEqualTo(7_000L)
    }

    private fun testClient(
        server: HttpServer,
        sleeper: suspend (Long) -> Unit = {},
    ): TenraiClient {
        val port = server.address.port
        return TenraiClient(
            baseClient = OkHttpClient(),
            baseUrl = "http://127.0.0.1:$port/v1/".toHttpUrl(),
            retryPolicy = TenraiRetryPolicy(
                maxAttempts = 3,
                initialBackoffMs = 1L,
                maxBackoffMs = 2L,
                maxRetryAfterMs = 5_000L,
            ),
            limiter = TenraiRateLimiter(
                maxPerSecond = 100,
                maxPerMinute = 1_000,
                minSpacingMs = 0L,
            ),
            sleeper = sleeper,
        )
    }

    private fun write(
        exchange: com.sun.net.httpserver.HttpExchange,
        code: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
