package com.sergey.animevault.data.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeProgressiveResumeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun resumesOnlyValidatedRepresentation() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        withServer({ exchange ->
            requests += exchange.requestHeaders.getFirst("Range") to exchange.requestHeaders.getFirst("If-Range")
            exchange.reply(206, "def", "\"r1\"", "bytes 3-5/6")
        }) { url ->
            seed(url)
            assertEquals("abcdef", download(url).readText())
        }
        assertEquals(listOf("bytes=3-" to "\"r1\""), requests)
    }

    @Test fun restartsForWrongOffsetChangedTagOr416() = runTest {
        for ((status, tag, range) in listOf(
            Triple(206, "\"r1\"", "bytes 1-3/4"),
            Triple(206, "\"r2\"", "bytes 3-5/6"),
            Triple(416, "\"r1\"", "bytes */3"),
        )) {
            val requests = Collections.synchronizedList(mutableListOf<String?>())
            withServer({ exchange ->
                val request = exchange.requestHeaders.getFirst("Range")
                requests += request
                if (request != null) exchange.reply(status, "BAD", tag, range)
                else exchange.reply(200, "new-complete", "\"r2\"")
            }) { url ->
                seed(url)
                assertEquals("new-complete", download(url).readText())
            }
            assertEquals(listOf("bytes=3-", null), requests)
        }
    }

    @Test fun ignoredRangeReplacesPreviousBytes() = runTest {
        withServer({ it.reply(200, "replacement", "\"r2\"") }) { url ->
            seed(url)
            assertEquals("replacement", download(url).readText())
        }
    }

    @Test fun shortBodyIsNeverFinalized() = runTest {
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, 10)
            try {
                exchange.responseBody.use { it.write("short".toByteArray()) }
            } catch (_: IOException) {
                exchange.close()
            }
        }) { url ->
            try {
                download(url)
                throw AssertionError("The incomplete response was finalized")
            } catch (_: IOException) {
                assertFalse(File(directory.root, "episode.mp4").exists())
            }
        }
    }

    private fun seed(url: String) {
        File(directory.root, "episode.mp4.partial").writeText("abc")
        File(directory.root, "episode.mp4.partial.source").writeText(
            downloadCacheKey("native-progressive", url) + "\n\"r1\"",
        )
    }

    private suspend fun download(url: String): File = NativeDownloadEngine(maxAttempts = 1, readTimeoutMs = 2_000)
        .download(DownloadMediaSource(url, emptyMap()), directory.root, "episode", null).file

    private suspend fun withServer(handle: (HttpExchange) -> Unit, action: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4", handle)
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/media.mp4")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(code: Int, body: String, tag: String, range: String? = null) {
        responseHeaders.set("ETag", tag)
        range?.let { responseHeaders.set("Content-Range", it) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
