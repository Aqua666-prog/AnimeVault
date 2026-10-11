package com.sergey.animevault.ui.online

import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalEpisodePlaylistTest {
    @Test fun m3uContainsSequentialItems() {
        val text = buildExternalM3u(listOf(
            ExternalPlaylistEntry("Серия 1", "https://example.org/1.m3u8"),
            ExternalPlaylistEntry("Серия 2", "https://example.org/2.mp4"),
        ))
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXTINF:-1,Серия 1\nhttps://example.org/1.m3u8"))
        assertTrue(text.indexOf("/1.m3u8") < text.indexOf("/2.mp4"))
    }

    @Test fun disallowMoreThanTwelveOrUnsafeLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            buildExternalM3u(List(13) { ExternalPlaylistEntry("Episode", "https://example.org/$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildExternalM3u(listOf(ExternalPlaylistEntry("Episode", "file:///sdcard/private")))
        }
    }
}
