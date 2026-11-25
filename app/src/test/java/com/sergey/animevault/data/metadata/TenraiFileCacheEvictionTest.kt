package com.sergey.animevault.data.metadata

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TenraiFileCacheEvictionTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun byteBudgetEvictsLeastRecentlyAccessed() = runTest {
        var time = 1_000L
        val cache = TenraiFileResponseCache(directory.root, nowMs = { time += 1_000L }, maxBytes = 250)
        val body = "a".repeat(75)
        cache.put("old-used", body)
        cache.put("old-unused", body)
        assertEquals(body, cache.get("old-used")?.body)
        cache.put("new", body)

        assertNull(cache.get("old-unused"))
        assertEquals(body, cache.get("old-used")?.body)
        assertEquals(body, cache.get("new")?.body)
    }

    @Test
    fun oversizedWriteDoesNotEvictExistingRecord() = runTest {
        val cache = TenraiFileResponseCache(directory.root, maxBytes = 100)
        cache.put("small", "cached")
        cache.put("large", "a".repeat(100))

        assertNull(cache.get("large"))
        assertEquals("cached", cache.get("small")?.body)
    }
}
