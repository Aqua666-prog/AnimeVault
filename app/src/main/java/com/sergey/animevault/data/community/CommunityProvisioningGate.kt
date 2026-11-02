package com.sergey.animevault.data.community

/** Bounded per-process negative cache; a refresh may always READ existing discussions. */
internal class CommunityProvisioningGate(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val attempts = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 128
    }
    fun supports(identity: CommunityIdentity): Boolean = identity.episodeExternal == null &&
        identity.provider in setOf("kodik", "animetka", "aniliberty", "yummy") && !identity.external.isNullOrBlank()

    /** Allow an explicit retry after fixing a server-side configuration error. */
    fun release(key: String) { attempts.remove(key) }

    /** Caller serializes claim+request with its coroutine Mutex. No timers or automatic retry loops. */
    fun claim(key: String): Boolean {
        val now = clock()
        val last = attempts[key]
        if (last != null && now - last < 300_000L) return false
        attempts[key] = now
        return true
    }
}
