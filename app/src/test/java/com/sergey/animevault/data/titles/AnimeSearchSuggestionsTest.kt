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
    @Test fun firstAndMiddleEditsReachOneEditMatcher() {
        listOf("Maruto", "aruto", "XNaruto", "Nxruto", "Nauto", "Naaruto", "NARUTO", "naruto", "наруто")
            .forEach { query -> assertEquals(query, "mal:1", index.search(query).firstOrNull()?.key) }
        listOf("Blech", "Bleech", "Bleaach").forEach { query ->
            assertEquals(query, "provider:one:2", index.search(query).firstOrNull()?.key)
        }
        assertTrue(index.search("unrelated title").isEmpty())
        assertTrue(index.search("Mr").isEmpty())
    }
    @Test fun exactAlwaysBeatsFuzzyAndRebuildDropsRemovedRecords() {
        val names = mapOf("local:1" to AnimeNames(name = "Maruto"), "local:2" to AnimeNames(name = "Naruto"))
        val before = AnimeSearchSuggestions(AnimeTitleIndex(names))
        assertEquals("local:1", before.search("Maruto").first().key)
        assertEquals(listOf(100, 30), before.search("Maruto").map { it.score })
        val after = AnimeSearchSuggestions(AnimeTitleIndex(names - "local:1"))
        assertEquals("local:2", after.search("Maruto").first().key)
        assertEquals(before.search("Naruto"), before.search("Naruto"))
    }
    @Test fun largeIndexBoundsPostingsAliasesAndFuzzyWork() {
        val names = (1..4000).associate { "local:$it" to AnimeNames(name = "Naruto fixture $it",
            synonyms = (1..25).map { alias -> "Alternate title $it number $alias" }) }
        val large = AnimeSearchSuggestions(AnimeTitleIndex(names))
        assertEquals("local:4000", large.search("Naruto fixture 4000").first().key)
        assertTrue(large.aliasCount <= 12000)
        assertTrue(large.fuzzyPostingCount <= 12000 * 79)
        listOf("Naruto", "Maruto", "Alternate title 55 number 1", "zzzzz").forEach {
            assertTrue(large.candidateCount(it) <= AnimeSearchSuggestions.MAX_CANDIDATES)
            assertTrue(large.search(it).size <= 8)
        }
        val singleAlias = AnimeSearchSuggestions(AnimeTitleIndex((1..3999).associate {
            "local:$it" to AnimeNames(name = "Fixture number $it")
        } + ("local:4000" to AnimeNames(name = "Naruto"))))
        assertEquals("local:4000", singleAlias.search("Maruto").first().key)
    }
}
