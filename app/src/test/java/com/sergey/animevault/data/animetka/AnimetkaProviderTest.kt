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

    @Test
    fun searchAttributes_doNotPoisonFullDetailsCache() = runTest {
        val api = CountingApi().apply {
            searchPayload = """[{"animetka_id":2778,"title":"Frieren","anime_poster_url":"/cover.webp","shikimori_id":52991}]"""
        }
        val provider = AnimetkaProvider(api)
        val result = provider.getCatalog(1, 20, "Frieren")
        assertThat(api.lastAttributes).containsExactlyElementsIn(ANIMETKA_SEARCH_ATTRIBUTES).inOrder()
        assertThat(result.releases.single().posterUrl).isEqualTo("https://animetka.com/cover.webp")
        provider.getRelease("2778")
        assertThat(api.detailsCalls).isEqualTo(1)
        provider.getCatalog(1, 20, "Frieren")
        provider.getRelease("2778")
        assertThat(api.detailsCalls).isEqualTo(1)
    }

    @Test
    fun failureOfDetailsCanBeRetriedAndIsNotCached() = runTest {
        val api = CountingApi().apply { detailFails = true }
        val provider = AnimetkaProvider(api)
        runCatching { provider.getRelease("2778") }.onSuccess { error("Expected failure") }
        api.detailFails = false
        assertThat(provider.getRelease("2778").id).isEqualTo("2778")
        assertThat(api.detailsCalls).isEqualTo(2)
    }

    @Test
    fun normalizedFieldsTakePrecedenceButFallbacksSurviveEmptyValues() {
        val dto = AnimetkaJson.parseAnime(JsonParser.parseString(
            """{"animetka_id":99,"title":"Season 2","title_en":"Second","anime_kind":"tv","type":"movie",
            "all_status":"ongoing","status":"completed","anime_genres":[],"genres":["Drama"],
            "anime_poster_url":"javascript:bad","poster_url":"/valid.webp",
            "anime_description":"  ","description":"Full synopsis",
            "episodes_total":10,"episodes_aired":28,"shikimori_id":777}""",
        ))!!
        assertThat(dto.type).isEqualTo("tv")
        assertThat(dto.status).isEqualTo("ongoing")
        assertThat(dto.genres).containsExactly("Drama")
        assertThat(dto.posterUrl).isEqualTo("/valid.webp")
        assertThat(dto.description).isEqualTo("Full synopsis")
        assertThat(dto.episodeCount).isNull()
        assertThat(dto.externalIds.shikimoriId).isEqualTo(777L)
        assertThat(dto.toOnlineCard().isOngoing).isTrue()
    }

    @Test
    fun fullSearchPageHasMore_shortAndEmptyPagesStop() = runTest {
        val api = CountingApi().apply {
            searchPayload = """[{"id":1,"title":"A"},{"id":2,"title":"B"}]"""
        }
        val provider = AnimetkaProvider(api)
        assertThat(provider.getCatalog(1, 2, "sample").canLoadMore).isTrue()
        api.searchPayload = """[{"id":3,"title":"C"}]"""
        assertThat(provider.getCatalog(2, 2, "sample").canLoadMore).isFalse()
        api.searchPayload = "[]"
        assertThat(provider.getCatalog(3, 2, "sample").canLoadMore).isFalse()
    }

    @Test
    fun repeatedServerPageStopsFurtherPagination() = runTest {
        val api = CountingApi().apply {
            searchPayload = """[{"id":1,"title":"A"},{"id":2,"title":"B"}]"""
        }
        val provider = AnimetkaProvider(api)
        assertThat(provider.getCatalog(1, 2, "repeat").canLoadMore).isTrue()
        assertThat(provider.getCatalog(2, 2, "repeat").canLoadMore).isFalse()
        assertThat(provider.paginationDiagnostic).contains("повторяет")
    }

    @Test
    fun explicitHasMoreAndMalformedPagesAreHandled() = runTest {
        val api = CountingApi().apply {
            searchPayload = """{"items":[{"id":1,"title":"A"}],"has_more":true}"""
        }
        val provider = AnimetkaProvider(api)
        assertThat(provider.getCatalog(1, 10, "explicit").canLoadMore).isTrue()
        api.searchPayload = """{"items":[{"ignored":1}],"has_more":true}"""
        runCatching { provider.getCatalog(2, 10, "explicit") }
            .onSuccess { error("Expected malformed page failure") }
    }

    @Test
    fun mainCollectionIsNotPaginated() = runTest {
        val provider = AnimetkaProvider(CountingApi())
        assertThat(provider.getCatalog(1, 20, "").canLoadMore).isFalse()
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
        var lastAttributes: List<String>? = null
        var detailsCalls = 0
        var detailFails = false
        var searchPayload: String = "[]"

        override suspend fun main(): JsonElement = JsonParser.parseString("[]")

        override suspend fun search(name: String, limit: Int, offset: Int, attributes: List<String>): JsonElement {
            lastLimit = limit
            lastOffset = offset
            lastAttributes = attributes
            return JsonParser.parseString(searchPayload)
        }

        override suspend fun details(id: String): JsonElement {
            detailsCalls++
            if (detailFails) throw java.io.IOException("Temporary details failure")
            return fixture("frieren_details.json")
        }

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
