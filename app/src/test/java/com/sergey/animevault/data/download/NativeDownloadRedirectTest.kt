package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStreamType
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NativeDownloadRedirectTest {
    @Test
    fun redirectedMediaPlaylist_usesEffectiveUrlForRelativeSegments() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/entry.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/cdn/media/index.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/media/index.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                ./segment.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/cdn/media/segment.ts") { exchange ->
            val body = ByteArray(4096) { (it and 0xff).toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/segment.ts") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = NativeDownloadEngine(maxAttempts = 1).probe(
                source = DownloadMediaSource(
                    url = "http://127.0.0.1:$port/entry.m3u8",
                    headers = emptyMap(),
                    streamType = OnlineStreamType.HLS,
                ),
                preferredQuality = null,
                forceHls = true,
            )
            assertThat(result.resolvedUrl).isEqualTo("http://127.0.0.1:$port/cdn/media/index.m3u8")
            assertThat(result.totalItems).isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun redirectedMasterAndMedia_useEachEffectiveUrlAsResolutionBase() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val correctSegmentHits = AtomicInteger()
        val wrongSegmentHits = AtomicInteger()

        server.createContext("/start/master.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/cdn/master/index.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/master/index.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
                ../video/720/playlist.m3u8
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/cdn/video/720/playlist.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/media/final/index.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/media/final/index.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                segment.ts?part=1,2
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/media/final/segment.ts") { exchange ->
            correctSegmentHits.incrementAndGet()
            val body = ByteArray(4096) { (it and 0xff).toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/cdn/video/720/segment.ts") { exchange ->
            wrongSegmentHits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = NativeDownloadEngine(maxAttempts = 1).probe(
                source = DownloadMediaSource(
                    url = "http://127.0.0.1:$port/start/master.m3u8",
                    headers = emptyMap(),
                    streamType = OnlineStreamType.HLS,
                ),
                preferredQuality = 720,
                forceHls = true,
            )
            assertThat(result.resolvedUrl).isEqualTo("http://127.0.0.1:$port/media/final/index.m3u8")
            assertThat(result.selectedQuality).isEqualTo(720)
            assertThat(correctSegmentHits.get()).isEqualTo(1)
            assertThat(wrongSegmentHits.get()).isEqualTo(0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun mediaParser_resolvesSegmentKeyAndMapAgainstEffectiveUrl() {
        val effectiveUrl = URI("https://cdn.example/final/path/index.m3u8")
        val parsed = HlsPlaylistParser.parse(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
                #EXT-X-MAP:URI="init/init.mp4"
                #EXTINF:4.0,
                segments/0001.m4s
                #EXT-X-ENDLIST
            """.trimIndent(),
            effectiveUrl,
        ) as HlsPlaylist.Media

        val segment = parsed.segments.single()
        assertThat(segment.uri).isEqualTo("https://cdn.example/final/path/segments/0001.m4s")
        assertThat(segment.key?.uri).isEqualTo("https://cdn.example/final/path/keys/key.bin")
        assertThat(segment.map?.uri).isEqualTo("https://cdn.example/final/path/init/init.mp4")
    }
}
