package com.sergey.animevault.data.cache

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InFlightRequestCacheTest {
    @Test
    fun concurrentSameKeyUsesOneLoader() = runTest {
        val calls = AtomicInteger()
        val cache = InFlightRequestCache<String, Int>()
        val results = List(8) {
            async {
                cache.getOrLoad("same") {
                    calls.incrementAndGet()
                    42
                }
            }
        }.awaitAll()
        assertEquals(List(8) { 42 }, results)
        assertEquals(1, calls.get())
    }

    @Test
    fun differentKeysDoNotShareValues() = runTest {
        val cache = InFlightRequestCache<String, String>()
        val first = cache.getOrLoad("a") { "A" }
        val second = cache.getOrLoad("b") { "B" }
        assertNotEquals(first, second)
    }

    @Test
    fun zeroTtlOnlyDeduplicatesInFlightRequests() = runTest {
        var calls = 0
        val cache = InFlightRequestCache<String, Int>(ttlMs = 0L)
        cache.getOrLoad("x") { ++calls }
        cache.getOrLoad("x") { ++calls }
        assertEquals(2, calls)
    }
}
