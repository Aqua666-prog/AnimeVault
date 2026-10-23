package com.sergey.animevault.data.animetka

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnimetkaExtrasRepositoryTest {
    @Test
    fun exactExternalId_mapsForeignReleaseToAnimetkaInternalId() = runTest {
        val api = SearchApi(
            """[
              {"id":2778,"title":"Frieren [TV-1]","title_en":"Frieren","shikimori_id":52991,"year":2023,"type":"TV"},
              {"id":9999,"title":"Frieren [TV-2]","title_en":"Frieren","shikimori_id":99999,"year":2026,"type":"TV"}
            ]""",
        )
        val repository = AnimetkaExtrasRepository(api)

        val id = repository.resolveAnimetkaId(release(shikimoriId = 52991L))

        assertThat(id).isEqualTo("2778")
        assertThat(api.searchCalls.get()).isEqualTo(1)
        assertThat(api.lastAttributes).containsExactlyElementsIn(ANIMETKA_SEARCH_ATTRIBUTES).inOrder()
    }

    @Test
    fun ambiguousTextMatch_isRejectedInsteadOfMixingSeasons() = runTest {
        val api = SearchApi(
            """[
              {"id":100,"title":"Example","title_en":"Example","year":2026,"type":"TV"},
              {"id":101,"title":"Example","title_en":"Example","year":2026,"type":"TV"}
            ]""",
        )
        val repository = AnimetkaExtrasRepository(api)

        val id = repository.resolveAnimetkaId(
            release(shikimoriId = null, name = "Example", englishName = "Example", year = 2026),
        )

        assertThat(id).isNull()
    }

    @Test
    fun disabledProvider_makesNoExtraNetworkRequests() = runTest {
        val api = SearchApi("[]")
        val repository = AnimetkaExtrasRepository(api, enabled = { false })

        assertThat(repository.resolveAnimetkaId(release())).isNull()
        assertThat(repository.trailersFor(release())).isEmpty()
        assertThat(api.searchCalls.get()).isEqualTo(0)
        assertThat(api.trailerCalls.get()).isEqualTo(0)
    }

    @Test
    fun failedSearch_isNotCachedAsNoMatch() = runTest {
        val api = SearchApi("""[{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}]""")
        val repository = AnimetkaExtrasRepository(api)
        api.searchError = IOException("offline")
        try {
            repository.charactersFor(release())
            throw AssertionError("Network error was swallowed")
        } catch (_: IOException) { }
        api.searchError = null
        assertThat(repository.resolveAnimetkaId(release(), requireExternalId = true)).isEqualTo("2778")
        assertThat(api.searchCalls.get()).isEqualTo(2)
    }

    @Test
    fun cancellation_propagatesAndDoesNotPoisonMappingCache() = runTest {
        val api = SearchApi("""[{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}]""")
        val repository = AnimetkaExtrasRepository(api)
        api.searchError = CancellationException("cancelled")
        try {
            repository.charactersFor(release())
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        api.searchError = null
        assertThat(repository.resolveAnimetkaId(release(), requireExternalId = true)).isEqualTo("2778")
    }

    @Test
    fun matchingMalIdCannotOverrideConflictingShikimoriId() = runTest {
        val api = SearchApi("""[{"animetka_id":2778,"title":"Frieren","shikimori_id":99999,"mal_id":123}]""")
        val target = release().copy(externalIds = ExternalAnimeIds(shikimoriId = 52991, malId = 123))
        assertThat(AnimetkaExtrasRepository(api).resolveAnimetkaId(target, requireExternalId = true)).isNull()
    }

    @Test
    fun malIdIsNeverUsedAsShikimoriId() = runTest {
        val api = SearchApi("""[{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}]""")
        val target = release().copy(externalIds = ExternalAnimeIds(malId = 52991))
        assertThat(AnimetkaExtrasRepository(api).resolveAnimetkaId(target, requireExternalId = true)).isNull()
    }

    @Test
    fun disabledCharacters_doNotRequestNetwork() = runTest {
        val api = SearchApi("[]")
        assertThat(AnimetkaExtrasRepository(api, enabled = { false }).charactersFor(release())).isNull()
        assertThat(api.searchCalls.get()).isEqualTo(0)
    }

    private fun release(
        shikimoriId: Long? = 52991L,
        name: String = "Frieren [TV-1]",
        englishName: String? = "Frieren",
        year: Int? = 2023,
    ) = OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK,
        providerName = "Kodik",
        id = "shiki:${shikimoriId ?: 0}",
        alias = name,
        name = name,
        englishName = englishName,
        posterUrl = null,
        year = year,
        type = "TV",
        season = "Season 1",
        episodeCount = 28,
        description = null,
        notification = null,
        genres = emptyList(),
        isOngoing = false,
        isBlocked = false,
        episodes = emptyList(),
        externalIds = ExternalAnimeIds(shikimoriId = shikimoriId),
    )

    private class SearchApi(private val searchJson: String) : AnimetkaApi {
        val searchCalls = AtomicInteger()
        val trailerCalls = AtomicInteger()
        var lastAttributes: List<String>? = null
        var searchError: Throwable? = null

        override suspend fun characters(animetkaId: String): JsonElement = JsonParser.parseString("[]")

        override suspend fun characterDetails(shikimoriCharacterId: String): JsonElement = JsonParser.parseString("{}")

        override suspend fun main(): JsonElement = JsonParser.parseString("[]")

        override suspend fun search(name: String, limit: Int, offset: Int, attributes: List<String>): JsonElement {
            searchCalls.incrementAndGet()
            searchError?.let { throw it }
            lastAttributes = attributes
            return JsonParser.parseString(searchJson)
        }

        override suspend fun details(id: String): JsonElement = JsonParser.parseString("""{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}""")

        override suspend fun playlist(materialId: String, translationId: String?): JsonElement =
            JsonParser.parseString("{}")

        override suspend fun trailers(id: String): JsonElement {
            trailerCalls.incrementAndGet()
            return JsonParser.parseString("[]")
        }
    }
}
