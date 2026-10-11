package com.sergey.animevault.ui.shikimori

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShikimoriCollectionParserTest {
    @Test fun acceptsOnlyShikimoriCollectionAndTopicLinks() {
        assertThat(parseShikimoriCollectionUrl("https://shikimori.one/collections/22482-anime-list"))
            .isEqualTo(ShikimoriCollectionRef(22482, false))
        assertThat(parseShikimoriCollectionUrl("https://shikimori.io/topics/635876"))
            .isEqualTo(ShikimoriCollectionRef(635876, true))
        assertThat(parseShikimoriCollectionUrl("https://evil.example/collections/22482")).isNull()
        assertThat(parseShikimoriCollectionUrl("https://shikimori.one.evil.example/collections/22482")).isNull()
        assertThat(parseShikimoriCollectionUrl("http://shikimori.one/collections/22482")).isNull()
        assertThat(parseShikimoriCollectionUrl("https://shikimori.one/collections/-1")).isNull()
    }

    @Test fun extractsDeduplicatedAnimeWithoutMangaOrCharacterIds() {
        val html = """
            <a href="https://shikimori.one/animes/5114-fullmetal-alchemist">Fullmetal Alchemist</a>
            <a href="/animes/5114">Duplicate</a>
            <a href='/animes/9253-steins-gate'><span>Steins;Gate</span></a>
            [anime=20]
            <a href="/mangas/999">Manga</a>
            <a href="/characters/888">Character</a>
        """.trimIndent()
        val items = parseShikimoriCollectionEntries(html)
        assertThat(items.map { it.id }).containsExactly(5114L, 9253L, 20L).inOrder()
        assertThat(items.first().title).isEqualTo("Fullmetal Alchemist")
        assertThat(items[1].title).isEqualTo("Steins;Gate")
    }

    @Test fun neverCreatesItemsFromRandomIntegers() {
        val items = parseShikimoriCollectionEntries("Year 2026, id 123, <a href='/mangas/503'>Manga</a>")
        assertThat(items).isEmpty()
    }
}
