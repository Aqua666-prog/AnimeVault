package com.sergey.animevault.data.online

import com.sergey.animevault.data.playback.PlaybackFailureKind
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthTrackerTest {
    @Test
    fun successUpdatesRuntimeHealthAndScore() {
        val tracker = ProviderHealthTracker()
        tracker.register(listOf("demo"))

        val state = tracker.recordSuccess(
            providerId = "demo",
            operation = ProviderOperation.CATALOG,
            latencyMs = 120L,
        )

        assertEquals(ProviderHealthStatus.AVAILABLE, state.status)
        assertEquals(1, state.successfulRequests)
        assertEquals(0, state.failedRequests)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(ProviderOperation.CATALOG, state.lastOperation)
        assertTrue(state.healthScore >= 60)
    }

    @Test
    fun failureClassifiesCauseAndPenalizesScore() {
        val tracker = ProviderHealthTracker()
        tracker.recordSuccess("demo", ProviderOperation.RELEASE, 100L)
        val healthyScore = tracker.states.value.getValue("demo").healthScore

        val failed = tracker.recordFailure(
            providerId = "demo",
            operation = ProviderOperation.STREAM,
            latencyMs = 2_500L,
            error = SocketTimeoutException("slow CDN"),
            sourceName = "Demo",
        )

        assertEquals(ProviderHealthStatus.DEGRADED, failed.status)
        assertEquals(PlaybackFailureKind.TIMEOUT, failed.lastFailureKind)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(1, failed.failedRequests)
        assertTrue(failed.healthScore < healthyScore)
    }

    @Test
    fun trackedOperationReturnsValueAndRecordsSuccess() = runBlocking {
        val tracker = ProviderHealthTracker()

        val result = tracker.track("demo", ProviderOperation.STREAM, "Demo") { "ok" }

        assertEquals("ok", result)
        assertEquals(ProviderHealthStatus.AVAILABLE, tracker.states.value.getValue("demo").status)
        assertEquals(1, tracker.states.value.getValue("demo").successfulRequests)
    }
}
