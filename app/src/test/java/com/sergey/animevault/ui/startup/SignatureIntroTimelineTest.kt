package com.sergey.animevault.ui.startup

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignatureIntroTimelineTest {
    @Test
    fun fullIntroFitsRequestedDurationAndAlwaysReleasesOverlay() {
        assertThat(SignatureIntroTimeline.DURATION_MS).isAtLeast(1_700)
        assertThat(SignatureIntroTimeline.DURATION_MS).isAtMost(2_000)
        assertThat(SignatureIntroTimeline.overlayAlpha(0f)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.overlayAlpha(SignatureIntroTimeline.DURATION_MS.toFloat())).isEqualTo(0f)
        assertThat(SignatureIntroTimeline.overlayAlpha(10_000f)).isEqualTo(0f)
        assertThat(SignatureIntroTimeline.MAX_LIFETIME_MS).isGreaterThan(SignatureIntroTimeline.DURATION_MS.toLong())
    }

    @Test
    fun linePrecedesMonogramAndWhitePrecedesWordmark() {
        assertThat(SignatureIntroTimeline.purpleTrace(180f)).isGreaterThan(0f)
        assertThat(SignatureIntroTimeline.aTrace(180f)).isEqualTo(0f)
        assertThat(SignatureIntroTimeline.aTrace(900f)).isGreaterThan(0f)
        assertThat(SignatureIntroTimeline.crossbarTrace(900f)).isEqualTo(0f)
        assertThat(SignatureIntroTimeline.whiteAlpha(1_180f)).isGreaterThan(0f)
        assertThat(SignatureIntroTimeline.wordmarkAlpha(1_180f)).isEqualTo(0f)
        assertThat(SignatureIntroTimeline.wordmarkAlpha(1_550f)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.overlayAlpha(1_550f)).isEqualTo(1f)
    }

    @Test
    fun allPhasesStayBoundedAndMonotonicIncludingOutsideDuration() {
        val phases = listOf<(Float) -> Float>(
            SignatureIntroTimeline::purpleTrace,
            SignatureIntroTimeline::purpleAlpha,
            SignatureIntroTimeline::aTrace,
            SignatureIntroTimeline::crossbarTrace,
            SignatureIntroTimeline::whiteAlpha,
            SignatureIntroTimeline::wordmarkAlpha,
            { 1f - SignatureIntroTimeline.overlayAlpha(it) },
        )
        phases.forEach { phase ->
            var previous = 0f
            for (time in -100..2_100) {
                val current = phase(time.toFloat())
                assertThat(current).isAtLeast(0f)
                assertThat(current).isAtMost(1f)
                assertThat(current).isAtLeast(previous)
                previous = current
            }
            assertThat(previous).isEqualTo(1f)
        }
    }

    @Test
    fun reducedMotionStartsWithCompletedGeometryAndUsesBriefDissolve() {
        val start = SignatureIntroTimeline.REDUCED_START_MS
        assertThat(SignatureIntroTimeline.purpleTrace(start)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.aTrace(start)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.crossbarTrace(start)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.whiteAlpha(start)).isEqualTo(1f)
        assertThat(SignatureIntroTimeline.REDUCED_DURATION_MS).isLessThan(500)
    }

    @Test
    fun frameClockCompletesFullAndReducedPlaybackWithinTheirBounds() = runTest {
        var lastFrame = 0f
        withContext(frameClock()) { playSignatureIntro(false, MutableStateFlow(true)) { lastFrame = it } }
        assertThat(testScheduler.currentTime).isAtLeast(1_700L)
        assertThat(testScheduler.currentTime).isAtMost(2_000L)
        assertThat(lastFrame).isEqualTo(SignatureIntroTimeline.DURATION_MS.toFloat())
        val reducedStart = testScheduler.currentTime
        withContext(frameClock()) { playSignatureIntro(true, MutableStateFlow(true)) { lastFrame = it } }
        assertThat(testScheduler.currentTime - reducedStart).isLessThan(500L)
        assertThat(lastFrame).isEqualTo(SignatureIntroTimeline.DURATION_MS.toFloat())
    }

    @Test
    fun missingSplashCallbackExitsAtWatchdogWithoutDrawing() = runTest {
        var frames = 0
        withContext(frameClock()) { playSignatureIntro(false, MutableStateFlow(false)) { frames++ } }
        assertThat(testScheduler.currentTime).isEqualTo(SignatureIntroTimeline.MAX_LIFETIME_MS)
        assertThat(frames).isEqualTo(0)
    }

    @Test
    fun stalledFrameClockAlsoExitsAtWatchdog() = runTest {
        val stalledClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = awaitCancellation()
        }
        withContext(stalledClock) { playSignatureIntro(false, MutableStateFlow(true)) {} }
        assertThat(testScheduler.currentTime).isEqualTo(SignatureIntroTimeline.MAX_LIFETIME_MS)
    }

    @Test
    fun waitingForSplashDoesNotAdvanceDrawingTimeline() = runTest {
        val ready = MutableStateFlow(false)
        var frames = 0
        val job = launch(frameClock()) { playSignatureIntro(false, ready) { frames++ } }
        advanceTimeBy(600)
        runCurrent()
        assertThat(frames).isEqualTo(0)
        ready.value = true
        advanceUntilIdle()
        assertThat(job.isCompleted).isTrue()
        assertThat(testScheduler.currentTime).isAtLeast(2_300L)
        assertThat(testScheduler.currentTime).isAtMost(2_600L)
        assertThat(frames).isGreaterThan(0)
    }

    @Test
    fun cancellationDoesNotCallCompletionOrKeepWaiting() = runTest {
        var completed = false
        val job = launch(frameClock()) {
            playSignatureIntro(false, MutableStateFlow(true)) {}
            completed = true
        }
        advanceTimeBy(300)
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertThat(job.isCancelled).isTrue()
        assertThat(completed).isFalse()
        assertThat(testScheduler.currentTime).isLessThan(SignatureIntroTimeline.MAX_LIFETIME_MS)
    }

    @Test
    fun systemMotionOffStopsPlaybackOnTheNextFrame() = runTest {
        var scale = 1f
        val systemMotion = object : MotionDurationScale {
            override val scaleFactor: Float get() = scale
        }
        var completed = false
        val job = launch(frameClock() + systemMotion) {
            playSignatureIntro(false, MutableStateFlow(true)) {}
            completed = true
        }
        advanceTimeBy(300)
        runCurrent()
        scale = 0f
        advanceUntilIdle()
        assertThat(job.isCompleted).isTrue()
        assertThat(completed).isTrue()
        assertThat(testScheduler.currentTime).isLessThan(350L)
    }

    private fun TestScope.frameClock(): MonotonicFrameClock = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(testScheduler.currentTime * 1_000_000)
        }
    }
}
