package com.sergey.animevault.ui.online

import com.sergey.animevault.data.titles.LocalTitleCandidate
import org.junit.Assert.*
import org.junit.Test

class LocalTitleNavigationTest {
    @Test fun localResultOpensItsActualRoomId() {
        var local: Long? = null
        openLocalTitleCandidate(LocalTitleCandidate("local:42", "Naruto", 100), { local = it },
            { _, _ -> fail("provider invented") }, { fail("unexpected fallback") })
        assertEquals(42L, local)
    }
    @Test fun providerResultPreservesReleaseIdIncludingColons() {
        var destination: Pair<String, String>? = null
        openLocalTitleCandidate(LocalTitleCandidate("provider:fixture:release:72", "Title", 100),
            { fail("local ID invented") }, { provider, release -> destination = provider to release }, { fail() })
        assertEquals("fixture" to "release:72", destination)
    }
    @Test fun metadataAndMissingIdsUseExistingSearchFallback() {
        listOf("mal:10", "anilist:10", "local:gone", "provider:fixture:", "local:-1").forEach { key ->
            var query = ""
            openLocalTitleCandidate(LocalTitleCandidate(key, "Naruto", 100), { fail("missing Room ID") },
                { _, _ -> fail("missing provider ID") }, { query = it })
            assertEquals("Naruto", query)
        }
    }
}
