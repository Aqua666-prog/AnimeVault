package com.sergey.animevault.data.animetka

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.util.concurrent.atomic.AtomicInteger
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

        override suspend fun main(): JsonElement = JsonParser.parseString("[]")

        override suspend fun search(name: String, limit: Int, offset: Int): JsonElement {
            searchCalls.incrementAndGet()
            return JsonParser.parseString(searchJson)
        }

        override suspend fun details(id: String): JsonElement = JsonParser.parseString("{}")

        override suspend fun playlist(materialId: String, translationId: String?): JsonElement =
            JsonParser.parseString("{}")

        override suspend fun trailers(id: String): JsonElement {
            trailerCalls.incrementAndGet()
            return JsonParser.parseString("[]")
        }
    }
}
