package com.sergey.animevault.data.kodik

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test

class KodikStreamResolverTest {
    @Test
    fun `страница плеера преобразуется в POST параметры`() {
        val page = parseKodikPlayerPage(
            """
            <html><head><script src="/assets/js/app.promo.js"></script></head>
            <script>
                var domain = 'kodik.info';
                var d_sign = 'domain-sign';
                var pd = 'player-domain';
                var pd_sign = 'player-sign';
                var ref = '';
                var ref_sign = 'ref-sign';
                vInfo.type = 'seria';
                vInfo.hash = 'hash-value';
                vInfo.id = '42';
            </script></html>
            """.trimIndent(),
        )

        assertThat(page.playerJsPath).isEqualTo("/assets/js/app.promo.js")
        assertThat(page.payload).containsExactly(
            "d", "kodik.info",
            "d_sign", "domain-sign",
            "pd", "player-domain",
            "pd_sign", "player-sign",
            "ref", "",
            "ref_sign", "ref-sign",
            "type", "seria",
            "hash", "hash-value",
            "id", "42",
        ).inOrder()
    }

    @Test
    fun `путь API извлекается из player JS`() {
        val script = "$.ajax({type: 'POST', url: atob('L2Z0b3I=')});"

        assertThat(extractKodikApiPath(script)).isEqualTo("/ftor")
    }

    @Test
    fun `ответ Kodik превращается в HLS качества с заголовками`() {
        val template = OnlineStream(
            id = "voice-11",
            quality = null,
            url = "https://kodik.info/seria/hash/episode/720p",
            type = OnlineStreamType.EMBED,
            translation = "Ancord",
            sourceName = "Kodik",
        )
        val streams = parseKodikLinks(
            responseJson = """
                {"links": {
                    "480": [{"src": "//cdn.example.org/video/480.mp4:hls/master.m3u8"}],
                    "720": [{"src": "//cdn.example.org/video/480.mp4:hls/master.m3u8"}]
                }}
            """.trimIndent(),
            template = template,
            playerUrl = template.url,
            userAgent = "AnimeVault-Test",
        )

        assertThat(streams.map(OnlineStream::quality)).containsExactly(720, 480).inOrder()
        assertThat(streams.first().url).contains("/720.mp4:")
        assertThat(streams.first().type).isEqualTo(OnlineStreamType.HLS)
        assertThat(streams.first().headers).containsEntry("User-Agent", "AnimeVault-Test")
        assertThat(streams.first().headers).containsEntry("Referer", template.url)
        assertThat(streams.first().headers).containsEntry("Origin", "https://kodik.info")
    }

    @Test
    fun `старый ROT Base64 ответ тоже декодируется`() {
        val directUrl = "https://cdn.example.org/episode/master.m3u8"
        val base64 = directUrl.encodeUtf8().base64()
        val encodedByKodik = rotateLatin(base64, 8)

        assertThat(decodeKodikSource(encodedByKodik)).isEqualTo(directUrl)
    }

    @Test
    fun `ссылки без схемы нормализуются а посторонний iframe отклоняется`() {
        assertThat(normalizeKodikPlayerUrl("//kodikplayer.com/seria/abc/1/720p"))
            .isEqualTo("https://kodikplayer.com/seria/abc/1/720p")
        assertThat(runCatching { normalizeKodikPlayerUrl("https://example.org/embed/42") }.isFailure)
            .isTrue()
    }

    private fun rotateLatin(value: String, shift: Int): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    in 'A'..'Z' -> 'A' + (character - 'A' + shift) % 26
                    in 'a'..'z' -> 'a' + (character - 'a' + shift) % 26
                    else -> character
                },
            )
        }
    }
}
