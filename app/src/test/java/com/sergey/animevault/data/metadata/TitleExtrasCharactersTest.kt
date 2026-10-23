package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.animetka.AnimetkaApi
import com.sergey.animevault.data.animetka.AnimetkaExtrasRepository
import com.sergey.animevault.data.animetka.createAnimetkaApi
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class TitleExtrasCharactersTest {
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val shikiFails = AtomicBoolean(true)
    private val animetkaFails = AtomicBoolean(false)
    private var shikiBody = SHIKI
    private var animetkaBody = CHARACTERS
    private var trailerBody = "[]"
    private var tenraiBody = """{"data":[]}"""
    private var searchBody = """[{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}]"""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        requests.add(request.url.toString())
        val (code, body) = when {
            request.url.host == "shikimori.one" -> if (shikiFails.get()) 522 to "timeout" else 200 to shikiBody
            path == "/api/anime/search" -> 200 to searchBody
            path == "/api/anime/2778" -> 200 to """{"animetka_id":2778,"title":"Frieren","shikimori_id":52991}"""
            path == "/api/anime/2778/characters" -> if (animetkaFails.get()) 503 to "unavailable" else 200 to animetkaBody
            path.endsWith("/trailers") -> 200 to trailerBody
            request.url.host == "animetka.com" -> 404 to "{}"
            path.endsWith("/characters") -> 200 to tenraiBody
            path.endsWith("/videos") -> 200 to """{"data":{}}"""
            else -> 200 to """{"anime":[],"data":[]}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()

    private fun repository(api: AnimetkaApi = createAnimetkaApi(client)) = TitleExtrasRepository(
        AnimeThemesClipRepository(client), TenraiExtrasRepository(TenraiClient(
            baseClient = client,
            retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
            limiter = TenraiRateLimiter(minSpacingMs = 0),
        )),
        ShikimoriExtrasRepository(client), AnimetkaExtrasRepository(api),
    )

    @Test fun shikimori522_usesVerifiedAnimetkaIdAndKeepsCharacterIdsSeparate() = runTest {
        val result = repository().getExtras(release())
        val fern = result.characters.single()
        assertThat(requests.any { "/api/anime/2778/characters" in it }).isTrue()
        assertThat(requests.none { "/api/anime/52991/characters" in it }).isTrue()
        assertThat(fern.id).isEqualTo("shiki:188176")
        assertThat(fern.shikimoriId).isEqualTo(188176L)
        assertThat(fern.malId).isNull()
        assertThat(fern.source).isEqualTo("Аниметка")
        assertThat(fern.role).isEqualTo("Главный персонаж")
        assertThat(fern.imageUrl).isEqualTo("https://shikimori.io/uploads/poster/characters/188176/fern.jpeg")
        assertThat(fern.description).contains("Фрирен")
        assertThat(fern.description).doesNotContain("[character=")
        assertThat(result.sources).contains("Аниметка")
        assertThat(result.charactersError).isNull()
    }

    @Test fun healthyShikimori_doesNotRequestFallbackCharacters() = runTest {
        shikiFails.set(false)
        assertThat(repository().getExtras(release()).characters.single().source).isEqualTo("Shikimori")
        assertThat(requests.none { it.endsWith("/characters") }).isTrue()
    }

    @Test fun failureIsNotCached_nextLoadRecoversWithoutForceRefresh() = runTest {
        animetkaFails.set(true)
        val repository = repository()
        val failed = repository.getExtras(release())
        assertThat(failed.characters).isEmpty()
        assertThat(failed.charactersError).isNotNull()
        animetkaFails.set(false)
        val recovered = repository.getExtras(release())
        assertThat(recovered.characters).hasSize(1)
        assertThat(recovered.charactersError).isNull()
        assertThat(requests.count { it.endsWith("/2778/characters") }).isEqualTo(2)
    }

    @Test fun failedRefresh_preservesLastSuccessfulCharactersAndCanRetry() = runTest {
        val repository = repository()
        val loaded = repository.getExtras(release())
        animetkaFails.set(true)
        val failed = repository.getExtras(release(), forceRefresh = true)
        assertThat(failed.characters).isEqualTo(loaded.characters)
        assertThat(failed.charactersAreStale).isTrue()
        assertThat(failed.charactersError).isNotNull()
        animetkaFails.set(false)
        assertThat(repository.getExtras(release()).charactersAreStale).isFalse()
    }

    @Test fun successfulEmptyResponse_hasNoCharacterError() = runTest {
        animetkaBody = "[]"
        val result = repository().getExtras(release())
        assertThat(result.characters).isEmpty()
        assertThat(result.charactersError).isNull()
    }

    @Test fun malformedFallback_isErrorAndIsRetried() = runTest {
        animetkaBody = "{}"
        val repository = repository()
        assertThat(repository.getExtras(release()).charactersError).isNotNull()
        animetkaBody = CHARACTERS
        assertThat(repository.getExtras(release()).characters).hasSize(1)
    }

    @Test fun graphqlErrorsAndMissingData_triggerFallback() = runTest {
        shikiFails.set(false)
        for (body in listOf("""{"errors":[{}],"data":{"animes":[]}}""", "{}")) {
            shikiBody = body
            assertThat(repository().getExtras(release()).characters.single().source).isEqualTo("Аниметка")
        }
    }

    @Test fun forceRefresh_bypassesSuccessfulShikimoriAndAggregateCaches() = runTest {
        shikiFails.set(false)
        val repository = repository()
        repository.getExtras(release())
        repository.getExtras(release())
        assertThat(requests.count { "shikimori.one" in it }).isEqualTo(1)
        repository.getExtras(release(), forceRefresh = true)
        assertThat(requests.count { "shikimori.one" in it }).isEqualTo(2)
    }

    @Test fun conflictingExternalId_cannotFallBackToSameName() = runTest {
        searchBody = """[{"animetka_id":2778,"title":"Frieren","shikimori_id":99999}]"""
        assertThat(repository().getExtras(release()).characters).isEmpty()
        assertThat(requests.none { it.endsWith("/2778/characters") }).isTrue()
    }

    @Test fun incompleteSearchIds_areVerifiedUsingDetails() = runTest {
        searchBody = """[{"animetka_id":2778,"title":"Frieren"}]"""
        assertThat(repository().getExtras(release()).characters).hasSize(1)
        assertThat(requests.any { it.endsWith("/api/anime/2778") }).isTrue()
    }

    @Test fun nativeAnimetkaTitle_skipsSearchAndUsesInternalId() = runTest {
        val release = release().copy(providerId = OnlineProviderIds.ANIMETKA, id = "2778")
        assertThat(repository().getExtras(release).characters).hasSize(1)
        assertThat(requests.none { "/api/anime/search" in it }).isTrue()
    }

    @Test fun numericShikiIdAndSameName_doNotInventMalIdentity() = runTest {
        tenraiBody = """{"data":[{"character":{"mal_id":188176,"name":"Fern"},"role":"Supporting"}]}"""
        val result = repository().getExtras(release().copy(
            externalIds = ExternalAnimeIds(shikimoriId = 52991, malId = 123),
        ))
        assertThat(result.characters).hasSize(2)
        val shiki = result.characters.single { it.shikimoriId != null }
        assertThat(shiki.malId).isNull()
        assertThat(shiki.role).isEqualTo("Главный персонаж")
    }

    @Test fun explicitMalId_enrichesOneCharacterAndKeepsAppearanceRole() = runTest {
        animetkaBody = CHARACTERS.replace("\"name\":\"Fern\"", "\"mal_id\":456,\"name\":\"Fern\"")
        tenraiBody = """{"data":[{"character":{"mal_id":456,"name":"Fern"},"role":"Supporting",
          "voice_actors":[{"person":{"mal_id":88,"name":"Actor"},"language":"Japanese"}]}]}"""
        val result = repository().getExtras(release().copy(
            externalIds = ExternalAnimeIds(shikimoriId = 52991, malId = 123),
        ))
        assertThat(result.characters).hasSize(1)
        assertThat(result.characters.single().malId).isEqualTo(456L)
        assertThat(result.characters.single().role).isEqualTo("Главный персонаж")
        assertThat(result.characters.single().voiceActors.single().name).isEqualTo("Actor")
    }

    @Test fun roleBelongsToTitle_notToGlobalCharacterId() = runTest {
        val repository = repository()
        assertThat(repository.getExtras(release()).characters.single().isMain).isTrue()
        animetkaBody = CHARACTERS.replace("Main", "Supporting")
        val otherTitle = release().copy(providerId = OnlineProviderIds.ANIMETKA, id = "2778")
        assertThat(repository.getExtras(otherTitle).characters.single().role).isEqualTo("Второстепенный персонаж")
    }

    @Test fun charactersFail_butAvailableVideoIsStillReturned() = runTest {
        animetkaFails.set(true)
        trailerBody = """[{"num":1,"url":"https://animetka.com/video.mp4"}]"""
        val result = repository().getExtras(release())
        assertThat(result.charactersError).isNotNull()
        assertThat(result.videos).hasSize(1)
        assertThat(result.videos.single().directUrl).isEqualTo("https://animetka.com/video.mp4")
    }

    @Test fun cancellationIsNotConvertedToSuccessfulEmptyResult() = runTest {
        var shouldCancel = true
        val baseApi = createAnimetkaApi(client)
        val api = object : AnimetkaApi by baseApi {
            override suspend fun characters(animetkaId: String): com.google.gson.JsonElement {
                if (shouldCancel) throw CancellationException("cancelled")
                return baseApi.characters(animetkaId)
            }
        }
        val repository = repository(api)
        try {
            repository.getExtras(release())
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        shouldCancel = false
        assertThat(repository.getExtras(release()).characters).hasSize(1)
    }

    @Test fun detailsEnrichCharacterWithoutReplacingTitleRole() = runTest {
        val api = object : AnimetkaApi by createAnimetkaApi(client) {
            override suspend fun characterDetails(shikimoriCharacterId: String): com.google.gson.JsonElement =
                com.google.gson.JsonParser.parseString("""{"character_shikimori_id":188176,"mal_id":456,
                  "name":"Fern","russian":"Ферн","description":"Справка","roles_en":["Supporting"]}""")
        }
        val repository = repository(api)
        val character = repository.getExtras(release()).characters.single()
        val details = repository.getCharacterDetails(character)
        assertThat(details.malId).isEqualTo(456L)
        assertThat(details.description).isEqualTo("Справка")
        assertThat(details.role).isEqualTo("Главный персонаж")
        assertThat(details.id).isEqualTo(character.id)
    }

    private fun release() = OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK, providerName = "Kodik", id = "52991", alias = "frieren",
        name = "Frieren", englishName = "Frieren", posterUrl = null, year = 2023, type = "TV",
        season = null, episodeCount = 28, description = null, notification = null, genres = emptyList(),
        isOngoing = false, isBlocked = false, episodes = emptyList(),
        externalIds = ExternalAnimeIds(shikimoriId = 52991),
    )

    companion object {
        const val CHARACTERS = """[{"character_shikimori_id":188176,"name":"Fern","russian":"Ферн",
          "poster_url":"https://shikimori.io/uploads/poster/characters/188176/fern.jpeg",
          "roles_en":["Main"],"roles_ru":["Main"],"description":"Ученица [character=184947]Фрирен[/character]."}]"""
        const val SHIKI = """{"data":{"animes":[{"id":"52991","name":"Frieren","characterRoles":[{
          "rolesEn":["Main"],"character":{"id":"188176","name":"Fern","russian":"Ферн"}}]}]}}"""
    }
}
