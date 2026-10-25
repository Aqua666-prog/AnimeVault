package com.sergey.animevault.data.online

import com.sergey.animevault.data.playback.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

class ProviderHttpFailureTest {
    @Test fun httpBoundaryPreserves429AndDoesNotExposeResponseBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("limited").header("Retry-After", "2")
                .body("SECRET_BODY".toResponseBody()).build()
        }.build()
        try {
            client.executeText(Request.Builder().url("https://example.invalid/").build(), "demo")
            fail("Expected HTTP failure")
        } catch (error: ProviderHttpException) {
            assertEquals(429, error.statusCode)
            assertEquals(2000L, error.retryAfterMs)
            assertFalse(error.message.orEmpty().contains("SECRET_BODY"))
        }
    }
    @Test fun statusSurvivesWrappingWithoutBodyOrUrl() {
        for ((code, kind) in listOf(401 to PlaybackFailureKind.AUTH_REQUIRED, 403 to PlaybackFailureKind.FORBIDDEN,
            404 to PlaybackFailureKind.NOT_FOUND, 429 to PlaybackFailureKind.RATE_LIMITED, 503 to PlaybackFailureKind.SERVER)) {
            val error = OnlineSourceException("request failed", ProviderHttpException("demo", code, 120000))
            val failure = PlaybackFailureClassifier.classify(error)
            assertEquals(kind, failure.kind)
            assertEquals(code, failure.httpCode)
            assertEquals(120000L, failure.retryAfterMs)
        }
    }
    @Test fun retryAfterSupportsSecondsAndHttpDate() {
        assertEquals(120000L, retryAfterMillis("120", 0))
        assertEquals(120000L, retryAfterMillis("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertNull(retryAfterMillis("bad"))
        assertNull(retryAfterMillis("-1"))
        assertNull(retryAfterMillis(null))
    }
    @Test fun first429DefersRuntimeRequestsAndKeepsPlaybackIndependent() {
        val tracker = ProviderHealthTracker()
        val state = tracker.recordFailure("demo", ProviderOperation.CATALOG, 10,
            ProviderHttpException("demo", 429, 120000))
        val until = state.channel(ProviderHealthChannel.CATALOG).cooldownUntilMs!!
        assertFalse(tracker.shouldAttempt("demo", ProviderHealthChannel.CATALOG, until - 1))
        assertTrue(tracker.shouldAttempt("demo", ProviderHealthChannel.CATALOG, until))
        assertTrue(tracker.shouldAttempt("demo", ProviderHealthChannel.PLAYBACK, until - 1))
    }
    @Test fun timeoutAndDnsRetainTheirOwnClassification() {
        assertEquals(PlaybackFailureKind.TIMEOUT, PlaybackFailureClassifier.classify(java.net.SocketTimeoutException()).kind)
        assertEquals(PlaybackFailureKind.DNS, PlaybackFailureClassifier.classify(java.net.UnknownHostException()).kind)
    }
}
