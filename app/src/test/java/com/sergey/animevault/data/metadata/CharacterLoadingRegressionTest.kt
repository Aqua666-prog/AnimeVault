package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.sergey.animevault.data.animetka.AnimetkaExtrasRepository
import com.sergey.animevault.data.animetka.createAnimetkaApi
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.util.Collections
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** No real network: the fake rejects the exact search attributes that production returns HTTP 500 for. */
class CharacterLoadingRegressionTest {
    @Test fun bleachAndNaruto_useSupportedProjectionAndVerifiedInternalIdAfter522() = runTest {
        for (case in cases) {
            val server = FakeApi(case)
            val result = repository(server).getExtras(release(case))
            assertThat(result.characters).hasSize(2)
            assertThat(result.charactersError).isNull()
            assertThat(result.characterDiagnostics.any {
                it.stage == CharacterStage.MAPPING && it.shikimoriAnimeId == case.shikiId &&
                    it.animetkaAnimeId == case.animetkaId.toLong()
            }).isTrue()
            assertThat(server.paths).contains("/api/anime/${case.animetkaId}/characters")
            assertThat(server.paths).doesNotContain("/api/anime/${case.shikiId}/characters")
            assertThat(result.characterDiagnostics.any {
                it.source == CharacterSource.SHIKIMORI && it.httpCode == 522
            }).isTrue()
            assertThat(result.characterDiagnostics.any {
                it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.CHARACTERS &&
                    it.state == CharacterLoadState.STARTED
            }).isTrue()
            assertThat(server.logs.any {
                it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.SEARCH && it.httpCode == 200
            }).isTrue()
        }
    }

    @Test fun search500_identifiesFailingStageAndIsNotCachedAsEmptySuccess() = runTest {
        val server = FakeApi(cases.first()).apply { rejectSearch = true }
        val repository = repository(server)
        val failed = repository.getExtras(release(server.case))
        assertThat(failed.charactersError).isNotNull()
        assertThat(failed.hasLoadErrors).isTrue()
        assertThat(failed.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.SEARCH &&
                it.httpCode == 500 && it.reason == CharacterLoadReason.HTTP_ERROR
        }).isTrue()
        assertThat(server.paths.none { it.endsWith("/characters") }).isTrue()
        server.rejectSearch = false
        assertThat(repository.getExtras(release(server.case)).characters).hasSize(2)
    }

    @Test fun successfulEmptyCharacterArray_isNotReportedAsNetworkFailure() = runTest {
        val server = FakeApi(cases.first()).apply { charactersBody = "[]" }
        val result = repository(server).getExtras(release(server.case))
        assertThat(result.charactersError).isNull()
        assertThat(result.characters).isEmpty()
        assertThat(result.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.state == CharacterLoadState.EMPTY && it.itemCount == 0
        }).isTrue()
    }

    @Test fun malformedCharacterPayload_isDiagnosedAndRetried() = runTest {
        val server = FakeApi(cases.first()).apply { charactersBody = "{}" }
        val repository = repository(server)
        val result = repository.getExtras(release(server.case))
        assertThat(result.charactersError).isNotNull()
        assertThat(result.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.CHARACTERS &&
                it.reason == CharacterLoadReason.INVALID_RESPONSE
        }).isTrue()
        assertThat(server.logs.any { it.stage == CharacterStage.CHARACTERS && it.httpCode == 200 }).isTrue()
        server.charactersBody = fixture("bleach-characters.json")
        assertThat(repository.getExtras(release(server.case)).characters).hasSize(2)
    }

    @Test fun http200ErrorObjectInSearch_isProtocolError_notNoMatch() = runTest {
        val server = FakeApi(cases.first()).apply { searchBody = fixture("search-http500.json") }
        val result = repository(server).getExtras(release(server.case))
        assertThat(result.charactersError).isNotNull()
        assertThat(result.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.SEARCH &&
                it.reason == CharacterLoadReason.INVALID_RESPONSE
        }).isTrue()
    }

    @Test fun malIdNumericallyEqualToShikimoriId_doesNotConfirmMapping() = runTest {
        val server = FakeApi(cases.first())
        val result = repository(server).getExtras(release(server.case).copy(
            externalIds = ExternalAnimeIds(malId = server.case.shikiId),
        ))
        assertThat(server.paths).doesNotContain("/api/anime/${server.case.animetkaId}/characters")
        assertThat(result.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.reason == CharacterLoadReason.NO_MATCH
        }).isTrue()
    }

    @Test fun missingIdsAndDisabledProvider_haveExplicitDiagnostics() = runTest {
        val server = FakeApi(cases.first())
        val noIds = repository(server).getExtras(release(server.case).copy(externalIds = ExternalAnimeIds()))
        assertThat(noIds.characterDiagnostics.any { it.reason == CharacterLoadReason.MISSING_IDS }).isTrue()
        val disabled = repository(server, enabled = false).getExtras(release(server.case))
        assertThat(disabled.characterDiagnostics.any { it.reason == CharacterLoadReason.DISABLED }).isTrue()
        assertThat(disabled.characterDiagnostics.any { it.reason == CharacterLoadReason.NO_MAL_ID }).isTrue()
    }

    @Test fun failedRefresh_keepsPreviousCharacters_andNextLoadRecovers() = runTest {
        val server = FakeApi(cases.first())
        val repository = repository(server)
        val initial = repository.getExtras(release(server.case))
        server.rejectCharacters = true
        val failed = repository.getExtras(release(server.case), forceRefresh = true)
        assertThat(failed.characters).isEqualTo(initial.characters)
        assertThat(failed.charactersAreStale).isTrue()
        assertThat(failed.characterDiagnostics.any {
            it.source == CharacterSource.ANIMETKA && it.stage == CharacterStage.CHARACTERS && it.httpCode == 503
        }).isTrue()
        server.rejectCharacters = false
        assertThat(repository.getExtras(release(server.case)).charactersAreStale).isFalse()
    }

    @Test fun diagnosticsContainNoResponseBodyUrlQueryOrCredentials() = runTest {
        val secret = "private-token-must-not-appear"
        val server = FakeApi(cases.first()).apply {
            rejectSearch = true
            errorBody = """{"error":"$secret Authorization: Bearer $secret Cookie=$secret"}"""
        }
        val result = repository(server).getExtras(release(server.case))
        val text = (result.characterDiagnostics + server.logs).joinToString("\n") { it.logLine() + it.summary() }
        assertThat(text).doesNotContain(secret)
        assertThat(text).doesNotContain("https://")
        assertThat(text).doesNotContain("attributes=")
        assertThat(text).doesNotContain("Authorization")
        assertThat(text).doesNotContain("Cookie")
        assertThat(text).contains("http=500")
    }

    @Test fun brokenDiagnosticConsumer_doesNotBreakHttpRequests() = runTest {
        val server = FakeApi(cases.first())
        val api = createAnimetkaApi(OkHttpClient.Builder().addInterceptor(server).build()) {
            error("diagnostics consumer failed")
        }
        assertThat(AnimetkaExtrasRepository(api).charactersFor(release(server.case))).hasSize(2)
    }

    private fun repository(server: FakeApi, enabled: Boolean = true): TitleExtrasRepository {
        val client = OkHttpClient.Builder().addInterceptor(server).build()
        return TitleExtrasRepository(
            animeThemes = AnimeThemesClipRepository(client),
            tenrai = TenraiExtrasRepository(TenraiClient(
                baseClient = client, retryPolicy = TenraiRetryPolicy(maxAttempts = 1),
                limiter = TenraiRateLimiter(minSpacingMs = 0),
            )),
            shikimori = ShikimoriExtrasRepository(client, diagnosticSink = { server.logs.add(it) }),
            animetka = AnimetkaExtrasRepository(createAnimetkaApi(client) { server.logs.add(it) }, enabled = { enabled }),
        )
    }

    private fun release(case: Case) = OnlineReleaseDetails(
        providerId = OnlineProviderIds.KODIK, providerName = "Kodik", id = "shiki:${case.shikiId}",
        alias = case.name, name = case.name, englishName = case.name, posterUrl = null,
        year = null, type = "TV", season = null, episodeCount = null, description = null, notification = null,
        genres = emptyList(), isOngoing = false, isBlocked = false, episodes = emptyList(),
        externalIds = ExternalAnimeIds(shikimoriId = case.shikiId),
    )

    private data class Case(val name: String, val shikiId: Long, val animetkaId: String)

    private class FakeApi(val case: Case) : Interceptor {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val logs = Collections.synchronizedList(mutableListOf<CharacterLoadDiagnostic>())
        var rejectSearch = false
        var rejectCharacters = false
        var errorBody = fixture("search-http500.json")
        var searchBody = fixture("${case.name.lowercase()}-search.json")
        var charactersBody = fixture("${case.name.lowercase()}-characters.json")

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            paths.add(path)
            val (code, body) = when {
                request.url.host == "shikimori.one" -> 522 to "upstream timeout"
                request.url.host != "animetka.com" -> 200 to """{"anime":[],"data":[]}"""
                path == "/api/anime/search" -> {
                    // The pre-fix production attributes produce 500 here, even though the URL is otherwise valid.
                    val invalidProjection = request.url.queryParameterValues("attributes").any {
                        it == "mal_id" || it == "anilist_id"
                    }
                    if (rejectSearch || invalidProjection) 500 to errorBody else 200 to searchBody
                }
                path == "/api/anime/${case.animetkaId}/characters" ->
                    if (rejectCharacters) 503 to errorBody else 200 to charactersBody
                path.endsWith("/trailers") -> 200 to "[]"
                path.matches(Regex("/api/anime/[0-9]+")) -> {
                    val id = path.substringAfterLast('/')
                    val candidate = JsonParser.parseString(fixture("${case.name.lowercase()}-search.json"))
                        .asJsonArray.first { it.asJsonObject["animetka_id"].asString == id }.asJsonObject
                    200 to """{"id":$id,"title":"fixture","shikimori_id":${candidate["shikimori_id"]}}"""
                }
                else -> 404 to "{}"
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).build()
        }
    }

    companion object {
        private val cases = listOf(Case("Bleach", 269, "2529"), Case("Naruto", 20, "3061"))
        private fun fixture(name: String): String =
            checkNotNull(CharacterLoadingRegressionTest::class.java.getResource("/animetka/$name")).readText()
    }
}
