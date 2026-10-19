package com.sergey.animevault.data.animetka

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AnimetkaApiRequestTest {
    @Test
    fun searchSerializesAttributesAsRepeatedParameters() = runTest {
        val requestedUrl = AtomicReference<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl.set(chain.request().url.toString())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("[]".toResponseBody())
                .build()
        }.build()

        createAnimetkaApi(client).search("Frieren", 20, 0, ANIMETKA_SEARCH_ATTRIBUTES)

        val url = checkNotNull(requestedUrl.get())
        assertThat(url).contains("/api/anime/search?name=Frieren&limit=20&offset=0")
        assertThat(Regex("(?:[?&])attributes=").findAll(url).count())
            .isEqualTo(ANIMETKA_SEARCH_ATTRIBUTES.size)
        assertThat(url).contains("attributes=shikimori_id")
        assertThat(url).contains("attributes=anime_poster_url")
    }
}
