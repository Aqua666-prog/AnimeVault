package com.sergey.animevault.data.titles

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AnimeTitleCachePolicyTest {
    @Test fun metadataOverflowNeverEvictsExistingLocals() {
        val local = AnimeTitleCachePolicy.merge(AnimeTitleIndex(), mapOf("local:7" to AnimeNames(name = "Naruto")))
        val filled = AnimeTitleCachePolicy.merge(local, (1..4500).associate { "mal:$it" to AnimeNames(name = "Title $it") })
        assertEquals(4000, filled.names.size)
        assertEquals("Naruto", filled.names["local:7"]?.name)
        val updated = AnimeTitleCachePolicy.merge(filled, mapOf("local:7" to AnimeNames(english = "NARUTO")))
        assertEquals("Naruto", updated.names["local:7"]?.name)
        assertEquals("NARUTO", updated.names["local:7"]?.english)
    }
    @Test fun deletedLocalsLoseOutgoingAndIncomingEdges() {
        val old = AnimeTitleIndex(mapOf("local:1" to AnimeNames(name = "Gone"), "local:2" to AnimeNames(name = "Keep"),
            "mal:1" to AnimeNames(name = "Metadata")), mapOf("local:1" to listOf("mal:1"),
            "mal:1" to listOf("local:1", "local:2")))
        val next = AnimeTitleCachePolicy.merge(old, emptyMap(), liveLocals = setOf(2))
        assertFalse("local:1" in next.names)
        assertFalse("local:1" in next.references)
        assertEquals(listOf("local:2"), next.references["mal:1"])
        assertTrue(AnimeSearchSuggestions(next).search("Gone").isEmpty())
    }
    @Test fun roundTripSanitizesAliasesReferencesAndKeepsRealKeys() {
        val old = AnimeTitleCachePolicy.merge(AnimeTitleIndex(), mapOf("local:4" to AnimeNames(name = "Naruto",
            synonyms = listOf(" Naruto ", "Naruto", "", "null"))),
            mapOf("local:4" to listOf("local:4", "mal:20", "mal:20", "local:999")))
        val restored = AnimeTitleCachePolicy.restore(Gson().toJson(old))
        assertEquals(old, restored)
        assertEquals(listOf("Naruto"), restored.names["local:4"]?.synonyms)
        assertEquals(listOf("mal:20"), restored.references["local:4"])
        assertEquals("local:4", AnimeSearchSuggestions(restored).search("Maruto").first().key)
    }
    @Test fun damagedCacheIsRebuildableAndBatchReplacesLocalAliases() {
        listOf("{broken", "null", "{\"names\":null}", "{\"names\":{\"local:1\":null}}",
            "{\"names\":{\"local:1\":{\"synonyms\":null}},\"references\":{}}")
            .forEach { assertEquals(it, AnimeTitleIndex(), AnimeTitleCachePolicy.restore(it)) }
        val old = AnimeTitleCachePolicy.merge(AnimeTitleIndex(), mapOf("local:1" to AnimeNames(name = "Old")))
        val next = AnimeTitleCachePolicy.merge(old, mapOf("local:1" to AnimeNames(name = "New")), liveLocals = setOf(1))
        assertEquals("New", next.names["local:1"]?.name)
        assertTrue(AnimeSearchSuggestions(next).search("Old").isEmpty())
    }
}
