package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiringScheduleClientTest {
    @Test fun usesAvailableTitleWhenEnglishIsJsonNull() {
        val payload = """{"data":{"Page":{"pageInfo":{"hasNextPage":false},"airingSchedules":[
            {"airingAt":1792000000,"episode":2,"media":{"id":321,"idMal":123,
             "countryOfOrigin":"JP","title":{"english":null,"romaji":"Tokyo Tale","native":"東京物語"},
             "coverImage":{"large":"https://example.org/poster.jpg"}}}
        ]}}}"""
        val (entries, more) = parseVaultAiringEpisodes(payload)
        assertThat(more).isFalse()
        assertThat(entries).hasSize(1)
        assertThat(entries[0].title).isEqualTo("Tokyo Tale")
        assertThat(entries[0].malId).isEqualTo(123)
        assertThat(entries[0].countryCode).isEqualTo("JP")
        assertThat(entries[0].searchTitle).isEqualTo("Tokyo Tale")
    }

    @Test fun literalNullDoesNotLeakToCardsAndCountryFilterWorks() {
        val payload = """{"data":{"Page":{"pageInfo":{"hasNextPage":false},"airingSchedules":[
            {"airingAt":1792000000,"episode":3,"media":{"id":1,"countryOfOrigin":"CN",
             "title":{"english":"null","romaji":null,"native":null}}},
            {"airingAt":1792000100,"episode":1,"media":{"id":2,"countryOfOrigin":"JP",
             "title":{"english":"Other anime","romaji":"Another title"}}}
        ]}}}"""
        val (entries, _) = parseVaultAiringEpisodes(payload)
        assertThat(entries[0].title).isEqualTo("Без названия · AniList #1")
        assertThat(entries[0].searchTitle).isEmpty()
        assertThat(entries[1].title).isEqualTo("Other anime")
        assertThat(entries[1].searchTitle).isEqualTo("Another title")
        assertThat(entries.map { it.countryCode }).containsExactly("CN", "JP").inOrder()
    }

    @Test fun russianOnlyGraphQlMetadataIgnoresNullAndRomanizedText() {
        val payload = """{"data":{"animes":[
            {"id":"123","russian":"История Токио"},
            {"id":"124","russian":null},
            {"id":"125","russian":"null"},
            {"id":"126","russian":"One Piece"}
        ]}}"""
        val translations = parseRussianAiringTitles(payload)
        assertThat(translations).containsExactly(123, "История Токио")
        val entries = listOf(
            VaultAiringEpisode(1, "Tokyo Tale", 2, 1792000000, null, 123, "JP", "Tokyo Tale"),
            VaultAiringEpisode(2, "One Piece", 3, 1792000001, null, 126, "JP", "One Piece"),
        )
        val localized = applyRussianAiringTitles(entries, translations)
        assertThat(localized[0].title).isEqualTo("История Токио")
        assertThat(localized[0].searchTitle).isEqualTo("Tokyo Tale")
        assertThat(localized[1].title).isEqualTo("One Piece")
    }

    @Test fun invalidGraphQlResponseIsRejected() {
        val payload = """{"errors":[{"message":"rate limit"}]}"""
        var thrown = false
        try { parseRussianAiringTitles(payload) } catch (_: IllegalStateException) { thrown = true }
        assertThat(thrown).isTrue()
    }
}
