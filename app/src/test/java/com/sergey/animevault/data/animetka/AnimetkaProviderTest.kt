package com.sergey.animevault.data.animetka

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.providerTranslationKey
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnimetkaProviderTest {
    @Test
    fun mappedFixture_preservesIdentityTranslationsStreamsAndSkipData() {
        val details = AnimetkaJson.parseAnime(resource("frieren_details.json"))!!
        val playlist = AnimetkaJson.parsePlaylist(resource("frieren_playlist_default.json"))
        val release = details.toOnlineDetails(
            playlist = playlist,
            selectedTranslation = playlist.translations.first { it.id == "609" },
            translations = playlist.translations,
        )

        assertThat(release.id).isEqualTo("2778")
        assertThat(release.externalIds.shikimoriId).isEqualTo(52991L)
        assertThat(release.availableTranslations).hasSize(33)
        assertThat(release.availableTranslations.any { it.key == providerTranslationKey(OnlineProviderIds.ANIMETKA, "2778", "610") }).isTrue()
        assertThat(release.episodes).hasSize(2)
        assertThat(release.episodes[1].skipData?.opening?.startMs).isEqualTo(1_000L)
        assertThat(release.episodes[1].skipData?.opening?.endMs).isEqualTo(89_000L)
        assertThat(release.episodes[1].streams).hasSize(2)
        assertThat(release.episodes[1].streams.map { it.quality }).containsExactly(720, 720)
        assertThat(release.episodes[1].streams.mapNotNull { it.hostFamily }).containsExactly("kodik", "anilibria")
        assertThat(release.episodes[1].streams.first().url).contains("code=a,b")
    }

    @Test
    fun playerJsParser_normalizesRelativeProtocolRelativeAndAbsoluteUrls() {
        val streams = parseAnimetkaPlayerJsStreams(
            raw = "[360]/video/a.m3u8?code=x,[480]//cdn.example/b.m3u8?token=y,[720]https://other.example/c.m3u8?q=1,2",
            releaseId = "1",
            episodeId = "s1",
            translationId = "609",
            translationName = "AniDUB",
            translationKey = providerTranslationKey(OnlineProviderIds.ANIMETKA, "1", "609"),
        )
        assertThat(streams).hasSize(3)
        assertThat(streams[0].url).isEqualTo("https://animetka.com/video/a.m3u8?code=x")
        assertThat(streams[1].url).isEqualTo("https://cdn.example/b.m3u8?token=y")
        assertThat(streams[2].url).isEqualTo("https://other.example/c.m3u8?q=1,2")
    }

    @Test
    fun releaseLoadsOnlyDefaultAndSelectedPlaylist_notEveryTranslation() = runTest {
        val api = CountingApi()
        val provider = AnimetkaProvider(api)

        val first = provider.getRelease("2778")
        assertThat(first.availableTranslations).hasSize(33)
        assertThat(api.playlistCalls.get()).isEqualTo(2)

        val selected = provider.getReleaseForTranslation(
            "2778",
            providerTranslationKey(OnlineProviderIds.ANIMETKA, "2778", "610"),
        )
        assertThat(selected.episodes.first().streams.first().quality).isEqualTo(1080)
        assertThat(api.playlistCalls.get()).isEqualTo(3)
    }



    @Test
    fun partialTranslation_containsOnlyItsRealEpisodes() = runTest {
        val api = CountingApi()
        val provider = AnimetkaProvider(api)
        provider.getRelease("2778")

        val partial = provider.getReleaseForTranslation(
            "2778",
            providerTranslationKey(OnlineProviderIds.ANIMETKA, "2778", "611"),
        )

        assertThat(partial.episodes.map { it.ordinal }).containsExactly(1.0)
        assertThat(partial.episodes.none { it.ordinal == 2.0 }).isTrue()
    }

    @Test
    fun invalidSkipRanges_areIgnoredWithoutBreakingEpisode() {
        val raw = JsonParser.parseString(
            """{"id":"1","title":"Test","shikimori_id":1}""",
        )
        val details = AnimetkaJson.parseAnime(raw)!!
        val playlist = AnimetkaJson.parsePlaylist(
            JsonParser.parseString(
                """{"translations":[{"value":"609","name":"Dub"}],"list":[{"id":"s1","file":"[720]https://cdn.example/a.m3u8","skipdata":{"opening":{"start":89,"stop":1},"ending":{"start":-1,"stop":10}}}]}""",
            ),
        )
        val release = details.toOnlineDetails(playlist, playlist.translations.single())
        assertThat(release.episodes.single().skipData).isNull()
    }

    @Test
    fun searchSecondPage_usesOffsetFromPageAndLimit() = runTest {
        val api = CountingApi()
        val provider = AnimetkaProvider(api)
        provider.getCatalog(page = 2, limit = 12, search = "Frieren")
        assertThat(api.lastOffset).isEqualTo(12)
        assertThat(api.lastLimit).isEqualTo(12)
    }

    private fun resource(name: String): JsonElement {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("animetka/$name"))
            .bufferedReader().use { it.readText() }
        return JsonParser.parseString(text)
    }

    private class CountingApi : AnimetkaApi {
        val playlistCalls = AtomicInteger()
        var lastOffset: Int? = null
        var lastLimit: Int? = null

        override suspend fun main(): JsonElement = JsonParser.parseString("[]")

        override suspend fun search(name: String, limit: Int, offset: Int): JsonElement {
            lastLimit = limit
            lastOffset = offset
            return JsonParser.parseString("[]")
        }

        override suspend fun details(id: String): JsonElement = fixture("frieren_details.json")

        override suspend fun playlist(materialId: String, translationId: String?): JsonElement {
            playlistCalls.incrementAndGet()
            if (translationId == "611") {
                return JsonParser.parseString(
                    """{"selected_translation_id":"611","list":[{"id":"s1","title":"1 серия","file":"[720]https://partial.example/s1.m3u8"}]}""",
                )
            }
            return fixture(if (translationId == "610") "frieren_playlist_610.json" else "frieren_playlist_default.json")
        }

        override suspend fun trailers(id: String): JsonElement = JsonParser.parseString("[]")

        private fun fixture(name: String): JsonElement {
            val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("animetka/$name"))
            return JsonParser.parseString(stream.bufferedReader().use { it.readText() })
        }
    }
}
