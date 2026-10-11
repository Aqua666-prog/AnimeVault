package com.sergey.animevault.data.kodik

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class KodikAutoAuthTest {
    @Test fun `does not fetch public token when bundled token works`() = runBlocking {
        var publicCalls = 0
        val fallback = KodikTokenFallback(
            personalToken = { null },
            bundledToken = { "bundled" },
            publicToken = { publicCalls++; "public" },
        )
        assertThat(fallback.execute { "ok:$it" }).isEqualTo("ok:bundled")
        assertThat(publicCalls).isEqualTo(0)
    }

    @Test fun `invalid personal token falls back to bundled`() = runBlocking {
        val calls = mutableListOf<String>()
        val fallback = KodikTokenFallback(
            personalToken = { "bad" },
            bundledToken = { "good" },
            publicToken = { error("public fetch not expected") },
        )
        val result = fallback.execute { token ->
            calls += token
            if (token == "bad") throw OnlineSourceException("Kodik: invalid token")
            "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(calls).containsExactly("bad", "good").inOrder()
    }

    @Test fun `without configured token public mode works`() = runBlocking {
        val fallback = KodikTokenFallback(
            personalToken = { null },
            bundledToken = { "" },
            publicToken = { "automatic" },
        )
        assertThat(fallback.execute { "using:$it" }).isEqualTo("using:automatic")
    }

    @Test fun `rejected public token refreshes once`() = runBlocking {
        val refreshed = mutableListOf<Boolean>()
        val fallback = KodikTokenFallback(
            personalToken = { null },
            bundledToken = { null },
            publicToken = { force -> refreshed += force; if (force) "new" else "old" },
        )
        val result = fallback.execute { token ->
            if (token == "old") throw OnlineSourceException("Kodik вернул ошибку 401")
            token
        }
        assertThat(result).isEqualTo("new")
        assertThat(refreshed).containsExactly(false, true).inOrder()
    }

    @Test fun `network errors never rotate tokens`() = runBlocking {
        var publicCalls = 0
        val fallback = KodikTokenFallback(
            personalToken = { "configured" },
            bundledToken = { null },
            publicToken = { publicCalls++; "public" },
        )
        try {
            fallback.execute<String> { throw OnlineSourceException("Не удалось подключиться к Kodik") }
            error("expected failure")
        } catch (error: OnlineSourceException) {
            assertThat(error.message).contains("подключиться")
        }
        assertThat(publicCalls).isEqualTo(0)
    }

    @Test fun `rate limits and geo blocks are not treated as invalid credentials`() {
        assertThat(isKodikCredentialError(OnlineSourceException("Kodik вернул ошибку 429"))).isFalse()
        assertThat(isKodikCredentialError(OnlineSourceException("Kodik вернул ошибку 403: blocked country"))).isFalse()
        assertThat(isKodikCredentialError(OnlineSourceException("Kodik: wrong API key"))).isTrue()
        assertThat(isKodikCredentialError(OnlineSourceException("Kodik вернул ошибку 401"))).isTrue()
    }

    @Test fun `cancellation propagates without retry`() = runBlocking {
        val fallback = KodikTokenFallback(
            personalToken = { "configured" },
            bundledToken = { "fallback" },
            publicToken = { "public" },
        )
        try {
            fallback.execute<String> { throw CancellationException("cancelled") }
            error("expected cancellation")
        } catch (_: CancellationException) {
            // success
        }
    }
}
