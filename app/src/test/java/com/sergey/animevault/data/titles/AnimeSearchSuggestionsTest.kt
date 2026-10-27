package com.sergey.animevault.data.titles

import org.junit.Assert.*
import org.junit.Test

class AnimeSearchSuggestionsTest {
    private val index = AnimeSearchSuggestions(AnimeTitleIndex(names = mapOf(
        "mal:1" to AnimeNames(russian = "Наруто", name = "Naruto", japanese = "ナルト"),
        "provider:one:2" to AnimeNames(russian = "Блич", name = "Bleach", synonyms = listOf("Buriichi")),
        "local:3" to AnimeNames(russian = "Клинок, рассекающий демонов", name = "Kimetsu no Yaiba")
    )))
    @Test fun multilingualAliasesUseExistingIds() {
        listOf("Наруто", "Naruto", "ナルト").forEach { assertEquals("mal:1", index.search(it).first().key) }
        listOf("Блич", "Bleach", "Buriichi").forEach { assertEquals("provider:one:2", index.search(it).first().key) }
        assertEquals("local:3", index.search("Kimetsu no Yaiba").first().key)
    }
    @Test fun oneTypoIsBoundedAndShortQueriesDoNotFuzz() {
        assertEquals("provider:one:2", index.search("Bleaach").first().key)
        assertTrue(index.search("Bb").isEmpty())
        assertTrue(index.search("x".repeat(81)).isEmpty())
        assertEquals(index.search("Bleach"), index.search("Bleach"))
    }
    @Test fun candidatePoolAndResultLimitStayBoundedWithLargeKnownLibrary() {
        val names = (1..4000).associate { "local:$it" to AnimeNames(name = "Naruto fixture $it") }
        val first = AnimeSearchSuggestions(AnimeTitleIndex(names = names)).search("Naruto")
        val reversed = AnimeSearchSuggestions(AnimeTitleIndex(names = names.entries.reversed().associate { it.toPair() })).search("Naruto")
        assertTrue(first.size <= 8)
        assertEquals(first, reversed)
        assertTrue(AnimeSearchSuggestions(AnimeTitleIndex()).search("Naruto").isEmpty())
    }
    @Test fun transliterationOnlyFindsCandidatesAndDoesNotMergeIds() {
        assertEquals("naruto", AnimeSearchSuggestions.transliterate("наруто"))
        val other = AnimeSearchSuggestions(AnimeTitleIndex(names = mapOf("local:1" to AnimeNames(name = "Naruto"))))
        assertEquals("local:1", other.search("наруто").first().key)
    }
}
