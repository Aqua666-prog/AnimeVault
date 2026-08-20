package com.sergey.animevault.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URI

class NativeDownloadEngineTest {
    @Test
    fun masterPlaylist_parsesVariantsAndChoosesPreferredHeight() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
            video/480.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2500000,RESOLUTION=1920x1080
            /video/1080.m3u8
            """.trimIndent(),
            URI("https://cdn.example/root/master.m3u8?token=one"),
        ) as HlsPlaylist.Master

        assertThat(playlist.variants).hasSize(2)
        assertThat(playlist.variants.first().codecs).isEqualTo("avc1.4d401f,mp4a.40.2")
        assertThat(playlist.variants.first().uri).isEqualTo("https://cdn.example/root/video/480.m3u8")
        assertThat(chooseHlsVariant(playlist.variants, 720)?.height).isEqualTo(480)
        assertThat(chooseHlsVariant(playlist.variants, null)?.height).isEqualTo(1080)
    }

    @Test
    fun mediaPlaylist_resolvesMapKeyRangesAndSequences() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:17
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin",IV=0x00000000000000000000000000000011
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1024@720
            media.mp4
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:2048
            media.mp4
            """.trimIndent(),
            URI("https://cdn.example/show/playlist.m3u8"),
        ) as HlsPlaylist.Media

        assertThat(playlist.mediaSequence).isEqualTo(17L)
        assertThat(playlist.isFragmentedMp4).isTrue()
        assertThat(playlist.segments.map(HlsSegment::sequence)).containsExactly(17L, 18L).inOrder()
        assertThat(playlist.segments.first().uri).isEqualTo("https://cdn.example/show/media.mp4")
        assertThat(playlist.segments.first().byteRange).isEqualTo(ByteRange(720, 1024))
        assertThat(playlist.segments.last().byteRange).isEqualTo(ByteRange(1744, 2048))
        assertThat(playlist.segments.first().map?.uri).isEqualTo("https://cdn.example/show/init.mp4")
        assertThat(playlist.segments.first().key?.uri).isEqualTo("https://cdn.example/show/keys/key.bin")
        assertThat(playlist.segments.first().key?.iv?.last()?.toInt()!! and 0xff).isEqualTo(17)
        assertThat(playlist.outputItems()).hasSize(3)
    }

    @Test
    fun transportStreamPlaylist_remainsTsAndKeyNoneClearsEncryption() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4,
            one.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            two.ts
            """.trimIndent(),
            URI("https://cdn.example/a/index.m3u8"),
        ) as HlsPlaylist.Media

        assertThat(playlist.isFragmentedMp4).isFalse()
        assertThat(playlist.segments.first().key?.method).isEqualTo("AES-128")
        assertThat(playlist.segments.last().key).isNull()
    }
}
