package com.sergey.animevault.data.animetka

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

class AnimetkaCharactersTest {
    @Test fun liveListFixture_parsesFourCharactersWithoutInventingMalIds() {
        val characters = parseAnimetkaCharacters(fixture("frieren-characters.json"))
        assertThat(characters.map { it.id }).containsExactly("188176", "184947", "188177", "196825").inOrder()
        assertThat(characters.first().russianName).isEqualTo("Ферн")
        assertThat(characters.first().malId).isNull()
        assertThat(characters.first().imageUrl).startsWith("https://shikimori.io/")
        assertThat(characters.first().roleEn).isEqualTo("Main")
    }

    @Test fun liveDetailFixture_preservesExplicitMalIdAndDoesNotApplyAppearanceRoles() {
        val character = parseAnimetkaCharacter(fixture("fern-details.json"))
        assertThat(character.id).isEqualTo("188176")
        assertThat(character.malId).isEqualTo(188176L)
        assertThat(character.roleEn).isNull()
        assertThat(character.description).doesNotContain("[character=")
    }

    @Test fun invalidPayloads_areErrorsRatherThanEmptySuccess() {
        for (json in listOf("{}", "null", "[{}]", "[{\"character_shikimori_id\":0,\"name\":\"A\"}]")) {
            assertThrows(IllegalStateException::class.java) { parseAnimetkaCharacters(JsonParser.parseString(json)) }
        }
        assertThat(parseAnimetkaCharacters(JsonParser.parseString("[]"))).isEmpty()
    }

    @Test fun posterNormalization_preservesIoHostAndRejectsNonHttpSchemes() {
        assertThat(normalizeCharacterPosterUrl("//shikimori.io/uploads/a.jpeg"))
            .isEqualTo("https://shikimori.io/uploads/a.jpeg")
        assertThat(normalizeCharacterPosterUrl("/uploads/a.jpeg"))
            .isEqualTo("https://shikimori.io/uploads/a.jpeg")
        assertThat(normalizeCharacterPosterUrl("javascript:alert(1)")).isNull()
        assertThat(normalizeCharacterPosterUrl("data:image/jpeg;base64,AA")).isNull()
    }

    @Test fun detailEndpoint_usesCharacterIdAndRejectsWrongIdentity() = runTest {
        var requestedPath: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedPath = chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(fixture("fern-details.json").toString().toResponseBody()).build()
        }.build()
        val repository = AnimetkaExtrasRepository(createAnimetkaApi(client))
        assertThat(repository.characterDetails("188176").russianName).isEqualTo("Ферн")
        assertThat(requestedPath).isEqualTo("/api/anime/characters/188176")
        try {
            repository.characterDetails("184947")
            throw AssertionError("Wrong character was accepted")
        } catch (_: IllegalStateException) { }
    }

    private fun fixture(name: String) = JsonParser.parseString(
        checkNotNull(javaClass.getResource("/animetka/$name")).readText(),
    )
}
