package com.sergey.animevault.ui.startup

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One frame clock drives every phase, including the dissolve into the already composed home. */
internal object SignatureIntroTimeline {
    const val DURATION_MS = 1_850
    const val REDUCED_DURATION_MS = 320
    const val REDUCED_START_MS = 1_260f
    const val MAX_LIFETIME_MS = 4_000L

    private val trace = CubicBezierEasing(.42f, 0f, .24f, 1f)
    private val dissolve = CubicBezierEasing(.4f, 0f, .2f, 1f)

    fun purpleTrace(timeMs: Float): Float = if (timeMs < 180f) {
        .12f * phase(timeMs, 0f, 180f, trace)
    } else {
        .12f + .88f * phase(timeMs, 180f, 680f, trace)
    }

    fun purpleAlpha(timeMs: Float): Float = phase(timeMs, 0f, 140f, dissolve)
    fun aTrace(timeMs: Float): Float = phase(timeMs, 560f, 1_040f, trace)
    fun crossbarTrace(timeMs: Float): Float = phase(timeMs, 900f, 1_120f, trace)
    fun whiteAlpha(timeMs: Float): Float = phase(timeMs, 1_040f, 1_260f, dissolve)
    fun wordmarkAlpha(timeMs: Float): Float = phase(timeMs, 1_190f, 1_460f, dissolve)
    fun overlayAlpha(timeMs: Float): Float = 1f - phase(timeMs, 1_550f, DURATION_MS.toFloat(), dissolve)

    private fun phase(timeMs: Float, start: Float, end: Float, easing: CubicBezierEasing): Float =
        easing.transform(((timeMs - start) / (end - start)).coerceIn(0f, 1f))
}

/** Cancellable playback also times out if the platform callback or frame clock stops arriving. */
internal suspend fun playSignatureIntro(
    reducedMotion: Boolean,
    ready: Flow<Boolean>,
    onFrame: (Float) -> Unit,
) {
    withTimeoutOrNull(SignatureIntroTimeline.MAX_LIFETIME_MS) {
        ready.first { it }
        val systemMotion = currentCoroutineContext()[MotionDurationScale]
        if (systemMotion?.scaleFactor == 0f) return@withTimeoutOrNull
        val initialTime = if (reducedMotion) SignatureIntroTimeline.REDUCED_START_MS else 0f
        val duration = if (reducedMotion) SignatureIntroTimeline.REDUCED_DURATION_MS else SignatureIntroTimeline.DURATION_MS
        val startedAt = withFrameNanos { it }
        var fraction: Float
        do {
            val frameTime = withFrameNanos { it }
            if (systemMotion?.scaleFactor == 0f) break
            fraction = ((frameTime - startedAt).toDouble() / (duration * 1_000_000.0)).toFloat().coerceIn(0f, 1f)
            onFrame(initialTime + (SignatureIntroTimeline.DURATION_MS - initialTime) * fraction)
        } while (fraction < 1f)
    }
}
