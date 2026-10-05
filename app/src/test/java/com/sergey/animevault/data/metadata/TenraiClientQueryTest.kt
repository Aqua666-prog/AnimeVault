package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test

class TenraiClientQueryTest {
    @Test
    fun queryParameters_areEncodedAsQueryNotPath() = runTest {
        var rawPath: String? = null
        var rawQuery: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/v1/anime/1/episodes") { exchange ->
            rawPath = exchange.requestURI.rawPath
            rawQuery = exchange.requestURI.rawQuery
            val body = "{\"data\":[]}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = TenraiClient(
                baseClient = OkHttpClient(),
                baseUrl = "http://127.0.0.1:$port/v1/".toHttpUrl(),
                retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                limiter = TenraiRateLimiter(maxPerSecond = 100, maxPerMinute = 1000, minSpacingMs = 0),
            )
            client.get(
                "anime/1/episodes",
                mapOf<String, String?>("page" to "2", "sfw" to null),
            )

            assertThat(rawPath).isEqualTo("/v1/anime/1/episodes")
            assertThat(rawQuery).contains("page=2")
            assertThat(rawQuery).contains("sfw")
        } finally {
            server.stop(0)
        }
    }
}
