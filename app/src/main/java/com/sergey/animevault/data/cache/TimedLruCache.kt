package com.sergey.animevault.data.cache

/** Thread-safe metadata cache with expiry measured from write time. */
internal class TimedLruCache<K, V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<V>(val value: V, val writtenAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (nowMs() - entry.writtenAt >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = Entry(value, nowMs())
        if (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
