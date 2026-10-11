package com.sergey.animevault.ui.shikimori

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShikimoriCollectionMetadataTest {
    @Test fun discoversOnlyCollectionTopicsAndPreservesOrder() {
        val json = """[
            {"id": 101, "linked_id": 20563, "linked_type": "Collection", "topic_title": "Аниме про моделей", "comments_count": 5},
            {"id": 102, "linked_id": 123, "linked_type": "Anime", "topic_title": "Не подборка"},
            {"id": 103, "linked_id": 22482, "linked_type": "Collection", "topic_title": "По сезонам"}
        ]""".trimIndent()
        val items = parseShikimoriCollectionCatalog(json)
        assertThat(items.map { it.collectionId }).containsExactly(20563L, 22482L).inOrder()
        assertThat(items[0].title).isEqualTo("Аниме про моделей")
        assertThat(items[0].commentsCount).isEqualTo(5)
    }

    @Test fun graphqlReplacesUnknownTitlesButKeepsMissingEntries() {
        val entries = listOf(
            ImportedShikiAnime(80L, "Аниме #80"),
            ImportedShikiAnime(790L, "Аниме #790"),
        )
        val json = """{"data":{"animes":[
            {"id":"80","name":"Mobile Suit Gundam","russian":"Мобильный воин Гандам", "score":"7.1", "airedOn":{"year":1979}, "poster":{"mainUrl":"https://shikimori.one/poster.jpg"}}
        ]}}""".trimIndent()
        val result = mergeShikimoriAnimeMetadata(entries, json)
        assertThat(result[0].title).isEqualTo("Мобильный воин Гандам")
        assertThat(result[0].year).isEqualTo(1979)
        assertThat(result[0].posterUrl).isEqualTo("https://shikimori.one/poster.jpg")
        assertThat(result[1].title).isEqualTo("Аниме #790")
    }

    @Test fun ignoresNonAnimeLinksAndDuplicateIds() {
        val html = """<a href='/mangas/88'>manga</a><a href='/animes/10'>Ten</a><a href='/animes/10'>Duplicate</a>"""
        val result = parseShikimoriCollectionEntries(html)
        assertThat(result.map { it.id }).containsExactly(10L)
    }
}
