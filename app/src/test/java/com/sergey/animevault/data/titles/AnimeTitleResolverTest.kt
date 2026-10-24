package com.sergey.animevault.data.titles

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AnimeTitleResolverTest {
    private val names = AnimeNames("Блич", "Bleach Romaji", "Bleach", "ブリーチ", listOf("BLEACH TV"))

    @Test fun resolvesAllLanguagesWithoutMutatingSource() {
        val source = "Provider title"
        assertEquals("Блич", AnimeTitleResolver.resolve(names, source, AnimeTitleLanguage.RUSSIAN))
        assertEquals("Bleach", AnimeTitleResolver.resolve(names, source, AnimeTitleLanguage.INTERNATIONAL))
        assertEquals("ブリーチ", AnimeTitleResolver.resolve(names, source, AnimeTitleLanguage.JAPANESE))
        assertEquals("Provider title", source)
        assertEquals("Bleach Romaji", names.name)
    }

    @Test fun fallbackOrderIsDeterministic() {
        val partial = AnimeNames(name = "Romaji", english = "English")
        assertEquals("English", AnimeTitleResolver.resolve(partial, "Original", AnimeTitleLanguage.RUSSIAN))
        assertEquals("Romaji", AnimeTitleResolver.resolve(partial, "Original", AnimeTitleLanguage.JAPANESE))
        assertEquals("Original", AnimeTitleResolver.resolve(AnimeNames(), "Original", AnimeTitleLanguage.JAPANESE))
        assertEquals("Alias", AnimeTitleResolver.resolve(AnimeNames(synonyms = listOf("Alias")), "", AnimeTitleLanguage.JAPANESE))
    }

    @Test fun blankAndJsonNullAreNotTranslations() {
        assertEquals("Original", AnimeTitleResolver.resolve(AnimeNames(" null ", "", "  ", "NULL"),
            "Original", AnimeTitleLanguage.RUSSIAN))
    }

    @Test fun aliasesAreSearchableRegardlessOfDisplayPreference() {
        for (query in listOf("блич", "bleach", "ブリーチ", "ROMAJI", "bleach tv", "Provider")) {
            assertTrue(query, AnimeTitleResolver.matches(names, "Provider title", query))
        }
        assertFalse(AnimeTitleResolver.matches(names, "Provider title", "Naruto"))
    }

    @Test fun defaultAndUnknownPreferenceAreRussianAndStoredChoicesRoundTrip() {
        assertEquals(AnimeTitleLanguage.RUSSIAN, AnimeTitleLanguage.fromStored(null))
        assertEquals(AnimeTitleLanguage.RUSSIAN, AnimeTitleLanguage.fromStored("removed-option"))
        for (language in AnimeTitleLanguage.entries) assertEquals(language, AnimeTitleLanguage.fromStored(language.name))
    }

    @Test fun equalNumbersInDifferentNamespacesDoNotJoin() {
        val index = AnimeTitleIndex(names = mapOf("mal:20" to names, "anilist:20" to AnimeNames(russian = "Другой тайтл")))
        assertEquals("Блич", index.namesFor(listOf("mal:20")).russian)
        assertEquals("Другой тайтл", index.namesFor(listOf("anilist:20")).russian)
        assertNull(index.namesFor(listOf("shikimori:20")).russian)
    }

    @Test fun sameNameDifferentSeasonsAreNotMerged() {
        val index = AnimeTitleIndex(names = mapOf("mal:1" to names, "mal:2" to names.copy(russian = "Блич: другой сезон")),
            references = mapOf("provider:kodik:season1" to listOf("mal:1"), "provider:kodik:season2" to listOf("mal:2")))
        assertEquals("Блич", index.namesFor(listOf("provider:kodik:season1")).russian)
        assertEquals("Блич: другой сезон", index.namesFor(listOf("provider:kodik:season2")).russian)
        assertNull(index.namesFor(listOf("provider:animevost:unmapped")).russian)
    }

    @Test fun persistentMetadataAndExplicitLocalLinkRoundTrip() {
        val index = AnimeTitleIndex(names = mapOf("anilist:269" to names), references = mapOf(localTitleKey(7) to listOf("anilist:269")))
        val loaded = Gson().fromJson(Gson().toJson(index), AnimeTitleIndex::class.java)
        assertEquals("ブリーチ", AnimeTitleResolver.resolve(loaded.namesFor(listOf(localTitleKey(7))), "Library original", AnimeTitleLanguage.JAPANESE))
        assertEquals("Library original", AnimeTitleResolver.resolve(loaded.namesFor(listOf(localTitleKey(8))), "Library original", AnimeTitleLanguage.JAPANESE))
    }

    @Test fun partialEnrichmentRetainsKnownNamesAndSynonyms() {
        val merged = names.overlay(AnimeNames(english = "Updated", synonyms = listOf("Another alias")))
        assertEquals("Блич", merged.russian)
        assertEquals("Updated", merged.english)
        assertTrue(merged.synonyms.containsAll(listOf("BLEACH TV", "Another alias")))
    }

    @Test fun queryExpansionIsBoundedAndRequiresAnExactKnownAlias() {
        val index = AnimeTitleIndex(names = mapOf("mal:20" to names))
        val queries = index.searchQueries("ブリーチ")
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.size <= 2)
        assertFalse("ブリーチ" in queries)
        assertTrue(index.searchQueries("ブリ").isEmpty())
    }
}
