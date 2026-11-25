package com.sergey.animevault.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedLruCacheTest {
    @Test fun boundsEntriesAndExpiresEvenAfterAHit() {
        var time = 0L
        val cache = TimedLruCache<String, String>(capacity = 2, ttlMs = 30L) { time }
        cache["old"] = "a"
        cache["unused"] = "b"
        time = 20
        assertEquals("a", cache["old"])
        cache["new"] = "c"
        assertNull(cache["unused"])
        time = 30
        assertNull(cache["old"])
        assertEquals("c", cache["new"])
    }
}
