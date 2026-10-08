package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStreamType
import java.io.File
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Real HTTP responses, including deliberate disconnects and deliberately broken Range servers. */
class ProgressiveDownloadIntegrityTest {
    private val original = ByteArray(96 * 1024) { index -> ((index * 31 + 7) % 251).toByte() }

    @Test
    fun truncatedResponseIsNotPublishedAndNextRequestResumesWithIfRange() = runBlocking {
        val calls = AtomicInteger()
        val resumeRange = AtomicReference<String?>()
        val resumeValidator = AtomicReference<String?>()
        withServer({ exchange ->
            val attempt = calls.incrementAndGet()
            if (attempt == 1) {
                send(exchange, 200, original.copyOfRange(0, 16 * 1024),
                    advertisedBytes = original.size.toLong(), etag = "\"version-1\"")
            } else {
                val offset = 16 * 1024
                resumeRange.set(exchange.requestHeaders.getFirst("Range"))
                resumeValidator.set(exchange.requestHeaders.getFirst("If-Range"))
                send(exchange, 206, original.copyOfRange(offset, original.size),
                    etag = "\"version-1\"",
                    contentRange = "bytes $offset-${original.size - 1}/${original.size}")
            }
        }) { url, directory ->
            val firstError = runCatching { download(url, directory) }.exceptionOrNull()
            assertThat(firstError).isInstanceOf(IOException::class.java)
            assertThat(File(directory, "episode.mp4").exists()).isFalse()
            assertThat(File(directory, "episode.mp4.partial").length()).isEqualTo(16L * 1024L)

            val result = download(url, directory)
            assertThat(result.file.readBytes().contentEquals(original)).isTrue()
            assertThat(resumeRange.get()).isEqualTo("bytes=16384-")
            assertThat(resumeValidator.get()).isEqualTo("\"version-1\"")
            assertThat(calls.get()).isEqualTo(2)
        }
    }

    @Test
    fun changedEtagAndFull200ResponseDiscardOldBytes() = runBlocking {
        val changed = ByteArray(original.size) { index -> (index * 19 % 253).toByte() }
        val range = AtomicReference<String?>()
        val ifRange = AtomicReference<String?>()
        val calls = AtomicInteger()
        withServer({ exchange ->
            if (calls.incrementAndGet() == 1) {
                send(exchange, 200, original.copyOfRange(0, 8 * 1024),
                    advertisedBytes = original.size.toLong(), etag = "\"old\"")
            } else {
                range.set(exchange.requestHeaders.getFirst("Range"))
                ifRange.set(exchange.requestHeaders.getFirst("If-Range"))
                // A conforming origin ignores Range when If-Range no longer matches.
                send(exchange, 200, changed, etag = "\"new\"")
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            val result = download(url, directory)
            assertThat(range.get()).isEqualTo("bytes=8192-")
            assertThat(ifRange.get()).isEqualTo("\"old\"")
            assertThat(result.file.readBytes().contentEquals(changed)).isTrue()
            assertThat(result.file.length()).isEqualTo(changed.size.toLong())
        }
    }

    @Test
    fun malformed206ContentRangeIsDiscardedThenFetchedFresh() = runBlocking {
        val calls = AtomicInteger()
        val secondRange = AtomicReference<String?>()
        val thirdRange = AtomicReference<String?>()
        withServer({ exchange ->
            when (calls.incrementAndGet()) {
                1 -> send(exchange, 200, original.copyOfRange(0, 4096),
                    advertisedBytes = original.size.toLong(), etag = "\"v1\"")
                2 -> {
                    secondRange.set(exchange.requestHeaders.getFirst("Range"))
                    // This is *not* the requested suffix. Appending it would corrupt the file.
                    send(exchange, 206, original, etag = "\"v1\"",
                        contentRange = "bytes 0-${original.size - 1}/${original.size}")
                }
                else -> {
                    thirdRange.set(exchange.requestHeaders.getFirst("Range"))
                    send(exchange, 200, original, etag = "\"v1\"")
                }
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            val result = download(url, directory)
            assertThat(secondRange.get()).isEqualTo("bytes=4096-")
            assertThat(thirdRange.get()).isNull()
            assertThat(calls.get()).isEqualTo(3)
            assertThat(result.file.readBytes().contentEquals(original)).isTrue()
        }
    }

    @Test
    fun http416NeverMarksExistingPartialComplete() = runBlocking {
        val calls = AtomicInteger()
        withServer({ exchange ->
            when (calls.incrementAndGet()) {
                1 -> send(exchange, 200, original.copyOfRange(0, 4096),
                    advertisedBytes = original.size.toLong(), etag = "\"v1\"")
                2 -> {
                    exchange.responseHeaders.add("Content-Range", "bytes */${original.size}")
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                }
                else -> send(exchange, 200, original, etag = "\"v1\"")
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            val result = download(url, directory)
            assertThat(calls.get()).isEqualTo(3)
            assertThat(result.file.readBytes().contentEquals(original)).isTrue()
        }
    }

    @Test
    fun noStrongValidatorRestartsFromZeroRatherThanUnsafeAppend() = runBlocking {
        val calls = AtomicInteger()
        val secondRange = AtomicReference<String?>()
        withServer({ exchange ->
            if (calls.incrementAndGet() == 1) {
                send(exchange, 200, original.copyOfRange(0, 8192),
                    advertisedBytes = original.size.toLong(), etag = "W/\"weak\"")
            } else {
                secondRange.set(exchange.requestHeaders.getFirst("Range"))
                send(exchange, 200, original, etag = "W/\"weak\"")
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            val result = download(url, directory)
            assertThat(secondRange.get()).isNull()
            assertThat(result.file.readBytes().contentEquals(original)).isTrue()
        }
    }

    @Test
    fun unsolicitedPartialResponseIsNeverAcceptedAsFullVideo() = runBlocking {
        withServer({ exchange ->
            send(exchange, 206, original.copyOfRange(0, 4096), etag = "\"v1\"",
                contentRange = "bytes 0-4095/${original.size}")
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.exceptionOrNull())
                .isInstanceOf(IOException::class.java)
            assertThat(File(directory, "episode.mp4").exists()).isFalse()
        }
    }

    @Test
    fun serverThatIgnoresIfRangeAndChangesEtagCannotCorruptThePartial() = runBlocking {
        val changed = ByteArray(original.size) { index -> (index * 17 % 239).toByte() }
        val calls = AtomicInteger()
        val requestedRange = AtomicReference<String?>()
        val finalRange = AtomicReference<String?>()
        withServer({ exchange ->
            when (calls.incrementAndGet()) {
                1 -> send(exchange, 200, original.copyOfRange(0, 8 * 1024),
                    advertisedBytes = original.size.toLong(), etag = "\"old\"")
                2 -> {
                    requestedRange.set(exchange.requestHeaders.getFirst("Range"))
                    val offset = 8 * 1024
                    // Broken origin returns 206 despite stale If-Range. ETag reveals the change.
                    send(exchange, 206, changed.copyOfRange(offset, changed.size),
                        etag = "\"new\"",
                        contentRange = "bytes $offset-${changed.size - 1}/${changed.size}")
                }
                else -> {
                    finalRange.set(exchange.requestHeaders.getFirst("Range"))
                    send(exchange, 200, changed, etag = "\"new\"")
                }
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            val result = download(url, directory)
            assertThat(requestedRange.get()).isEqualTo("bytes=8192-")
            assertThat(finalRange.get()).isNull()
            assertThat(calls.get()).isEqualTo(3)
            assertThat(result.file.readBytes().contentEquals(changed)).isTrue()
        }
    }

    @Test
    fun truncated206IsNotMarkedCompleteAndCanResumeAgain() = runBlocking {
        val calls = AtomicInteger()
        val lastRange = AtomicReference<String?>()
        withServer({ exchange ->
            when (calls.incrementAndGet()) {
                1 -> send(exchange, 200, original.copyOfRange(0, 8192),
                    advertisedBytes = original.size.toLong(), etag = "\"v1\"")
                2 -> send(exchange, 206, original.copyOfRange(8192, 12288),
                    advertisedBytes = (original.size - 8192).toLong(), etag = "\"v1\"",
                    contentRange = "bytes 8192-${original.size - 1}/${original.size}")
                else -> {
                    lastRange.set(exchange.requestHeaders.getFirst("Range"))
                    send(exchange, 206, original.copyOfRange(12288, original.size),
                        etag = "\"v1\"",
                        contentRange = "bytes 12288-${original.size - 1}/${original.size}")
                }
            }
        }) { url, directory ->
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            assertThat(runCatching { download(url, directory) }.isFailure).isTrue()
            assertThat(File(directory, "episode.mp4.partial").length()).isEqualTo(12288L)
            val result = download(url, directory)
            assertThat(lastRange.get()).isEqualTo("bytes=12288-")
            assertThat(result.file.readBytes().contentEquals(original)).isTrue()
        }
    }

    @Test
    fun rangeParserRejectsImpossibleAndAmbiguousRanges() {
        assertThat(parseProgressiveContentRange("bytes 10-19/20"))
            .isEqualTo(ProgressiveContentRange(10, 19, 20))
        assertThat(parseProgressiveContentRange("bytes 10-19/20")?.length).isEqualTo(10L)
        assertThat(parseProgressiveContentRange("bytes 10-19/*")).isNull()
        assertThat(parseProgressiveContentRange("bytes */20")).isNull()
        assertThat(parseProgressiveContentRange("bytes 10-20/20")).isNull()
        assertThat(parseProgressiveContentRange("bytes 20-10/30")).isNull()
        assertThat(parseProgressiveContentRange("bytes 99999999999999999999-20/30")).isNull()
    }

    private suspend fun download(url: String, directory: File): NativeDownloadResult =
        NativeDownloadEngine(maxAttempts = 1).download(
            source = DownloadMediaSource(url = url, headers = emptyMap(), streamType = OnlineStreamType.MP4),
            targetDirectory = directory,
            fileStem = "episode",
            preferredQuality = null,
        )

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        test: suspend (String, File) -> Unit,
    ) {
        // Raw HTTP intentionally closes the socket after each response. This makes a
        // declared-but-truncated Content-Length deterministic, unlike HttpServer's
        // connection pool which can keep deliberately broken exchanges alive.
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(name = "AnimeVault-MP4-TestServer", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val requestHeaders = TestHeaders()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        if (reader.readLine() == null) return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) requestHeaders.add(line.substring(0, separator), line.substring(separator + 1).trim())
                        }
                        handler(HttpExchange(socket, requestHeaders))
                    }
                } catch (_: SocketException) {
                    if (server.isClosed) break
                } catch (_: SocketTimeoutException) {
                    // A stalled test request times out rather than pinning the JVM.
                } catch (_: IOException) {
                    if (server.isClosed) break
                }
            }
        }
        val directory = Files.createTempDirectory("animevault-range-").toFile()
        try {
            test("http://127.0.0.1:${server.localPort}/video.mp4", directory)
        } finally {
            server.close()
            worker.join(3_000)
            directory.deleteRecursively()
        }
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        bytes: ByteArray,
        advertisedBytes: Long = bytes.size.toLong(),
        etag: String? = null,
        contentRange: String? = null,
    ) {
        exchange.responseHeaders.add("Connection", "close")
        exchange.responseHeaders.add("Content-Type", "video/mp4")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        etag?.let { exchange.responseHeaders.add("ETag", it) }
        contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
        exchange.sendResponseHeaders(status, advertisedBytes)
        try {
            exchange.responseBody.use { output -> output.write(bytes) }
        } catch (_: IOException) {
            // A test deliberately closes a fixed-length HTTP response prematurely.
        } finally {
            exchange.close()
        }
    }
}

/** Test-only HTTP exchange: sends raw response bytes and closes even if Content-Length lies. */
private class HttpExchange(
    private val socket: Socket,
    val requestHeaders: TestHeaders,
) {
    val responseHeaders = TestHeaders()
    val responseBody = ByteArrayOutputStream()
    private var status = 200
    private var declaredBytes = 0L
    private var sent = false

    fun sendResponseHeaders(code: Int, bytes: Long) {
        status = code
        declaredBytes = bytes.coerceAtLeast(0L)
    }

    fun close() {
        if (sent) return
        sent = true
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val headerText = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: $declaredBytes\r\n")
            append("Connection: close\r\n")
            responseHeaders.entries().forEach { (key, value) ->
                if (!key.equals("Connection", ignoreCase = true)) append("$key: $value\r\n")
            }
            append("\r\n")
        }
        socket.getOutputStream().use { stream ->
            stream.write(headerText.toByteArray(StandardCharsets.ISO_8859_1))
            stream.write(responseBody.toByteArray())
            stream.flush()
        }
        socket.close()
    }
}

private class TestHeaders {
    private val values = linkedMapOf<String, String>()
    fun add(key: String, value: String) {
        values[key.lowercase()] = value
    }
    fun getFirst(key: String): String? = values[key.lowercase()]
    fun entries(): Map<String, String> = values
}
