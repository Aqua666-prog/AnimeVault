package com.sergey.animevault.data.cache

import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small coroutine-safe cache that also coalesces concurrent requests for the same key.
 * A zero TTL disables value caching while preserving in-flight de-duplication.
 */
class InFlightRequestCache<K, V>(
    private val maxEntries: Int = 64,
    private val ttlMs: Long = 5 * 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMs >= 0L) { "ttlMs must not be negative" }
    }

    private data class CacheEntry<V>(val value: V, val storedAtMs: Long)

    private val mutex = Mutex()
    private val values = object : LinkedHashMap<K, CacheEntry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean =
            size > maxEntries
    }
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun getOrLoad(
        key: K,
        forceRefresh: Boolean = false,
        cacheIf: (V) -> Boolean = { true },
        loader: suspend () -> V,
    ): V {
        while (true) {
            var owner = false
            val request = mutex.withLock {
                if (!forceRefresh && ttlMs > 0L) {
                    val cached = values[key]
                    if (cached != null && nowMs() - cached.storedAtMs <= ttlMs) return cached.value
                    values.remove(key)
                }
                inFlight[key] ?: CompletableDeferred<V>().also {
                    inFlight[key] = it
                    owner = true
                }
            }
            if (!owner) {
                try {
                    return request.await()
                } catch (cancelled: CancellationException) {
                    // A caller leaving its screen must not cancel another caller waiting for the key.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val value = loader()
                mutex.withLock {
                    if (ttlMs > 0L && cacheIf(value)) values[key] = CacheEntry(value, nowMs())
                    else values.remove(key)
                    inFlight.remove(key)
                    request.complete(value)
                }
                return value
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        inFlight.remove(key)
                        request.completeExceptionally(error)
                    }
                }
                throw error
            }
        }
    }

    suspend fun invalidate(key: K) {
        mutex.withLock { values.remove(key) }
    }

    suspend fun invalidateWhere(predicate: (K) -> Boolean) {
        mutex.withLock {
            values.keys.removeAll(predicate)
        }
    }

    suspend fun clear() {
        mutex.withLock { values.clear() }
    }

    suspend fun cachedEntryCount(): Int = mutex.withLock { values.size }
    suspend fun inFlightCount(): Int = mutex.withLock { inFlight.size }
}
