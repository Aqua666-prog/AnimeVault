package com.sergey.animevault.ui.online

import com.sergey.animevault.data.online.*
import com.sergey.animevault.data.titles.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class IndependentCatalogSearchTest {
    private val hit = LocalTitleCandidate("local:7", "Naruto", 100)
    private class FakeProvider : OnlineProvider {
        override val descriptor = OnlineProviderDescriptor("fixture", "Fixture", "No network")
        var requests = 0
        var response: suspend () -> OnlineCatalogPage = { OnlineCatalogPage(emptyList(), 1, 1) }
        override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage { requests++; return response() }
        override suspend fun getRelease(id: String): OnlineReleaseDetails = error("Not used")
    }

    @Test fun offlineErrorKeepsLocalResultsAndPublishesBeforeNetworkCompletes() = runTest {
        val provider = FakeProvider()
        var state = OnlineCatalogUiState()
        provider.response = {
            assertEquals(listOf(hit), state.localMatches)
            throw IOException("offline")
        }
        loadCatalogFirstPage("Naruto", true, { true }, { listOf(hit) },
            { state = state.copy(localMatches = it) }, { provider.getCatalog(1, search = "Naruto") },
            { fail("offline") }, { state = state.copy(errorMessage = "offline") })
        assertEquals(listOf(hit), state.localMatches)
        assertEquals("offline", state.errorMessage)
        assertEquals(1, provider.requests)
    }
    @Test fun unsupportedSearchAndShortQueryStillUseLocalIndexWithoutNetwork() = runTest {
        val provider = FakeProvider()
        listOf(provider.descriptor.copy(capabilities = ProviderCapabilities(search = false)),
            provider.descriptor.copy(minimumSearchLength = 20)).forEach { descriptor ->
            var matches = emptyList<LocalTitleCandidate>()
            val canRequest = catalogCanRequest(descriptor, "Naruto")
            assertFalse(canRequest)
            loadCatalogFirstPage("Naruto", canRequest, { true }, { listOf(hit) }, { matches = it },
                { provider.getCatalog(1) }, { fail("network forbidden") }, { fail("unexpected failure") })
            assertEquals(listOf(hit), matches)
        }
        assertEquals(0, provider.requests)
    }
    @Test fun changedProviderAndAtoBtoARejectLateLocalAndNetworkCallbacks() = runTest {
        for (duringLocal in listOf(true, false)) {
            var generation = 1L
            var providerId = "one"
            val deferred = CompletableDeferred<Unit>()
            var local = emptyList<LocalTitleCandidate>()
            var networkUpdates = 0
            val job = launch {
                loadCatalogFirstPage("A", true,
                    { catalogRequestIsCurrent(1, generation, "one", providerId, "A", "A") },
                    { if (duringLocal) withContext(NonCancellable) { deferred.await() }; listOf(hit) },
                    { local = it },
                    { withContext(NonCancellable) { deferred.await() }; "stale" },
                    { networkUpdates++ }, { fail("stale error") })
            }
            runCurrent()
            generation = 3 // A→B→A, text equality must not revive generation1.
            providerId = "two"
            local = emptyList()
            deferred.complete(Unit)
            job.join()
            assertTrue(local.isEmpty())
            assertEquals(0, networkUpdates)
        }
    }
    @Test fun emptyQueryClearsLocalWithoutConsultingIndexAndPartialSuccessKeepsBoth() = runTest {
        var local = listOf(hit)
        loadCatalogFirstPage("", false, { true }, { error("empty query lookup") }, { local = it },
            { error("network") }, {}, {})
        assertTrue(local.isEmpty())
        var remote = ""
        loadCatalogFirstPage("Naruto", true, { true }, { listOf(hit) }, { local = it },
            { "partial fixture" }, { remote = it }, { fail() })
        assertEquals(listOf(hit), local)
        assertEquals("partial fixture", remote)
        val cleared = OnlineCatalogUiState(localMatches = local, localSuggestions = listOf("Old"), canLoadMore = true)
            .clearSearchResults()
        assertTrue(cleared.localMatches.isEmpty())
        assertTrue(cleared.localSuggestions.isEmpty())
        assertFalse(cleared.canLoadMore)
    }
    @Test fun cancellationPropagatesWithoutPublishingFailure() = runTest {
        try {
            loadCatalogFirstPage("Naruto", true, { true }, { listOf(hit) }, {},
                { throw CancellationException("cancel") }, { fail() }, { fail("cancellation swallowed") })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
