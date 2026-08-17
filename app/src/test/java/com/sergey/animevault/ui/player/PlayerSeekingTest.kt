package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSeekingTest {
    @Test
    fun `horizontal drag uses a bounded quarter-duration window`() {
        val target = calculateSwipeSeekTarget(
            startPositionMs = 10 * 60_000L,
            durationMs = 24 * 60_000L,
            dragFraction = 0.5f,
        )

        // Для 24 минут полный экран равен максимум пяти минутам, половина — 2:30.
        assertThat(target).isEqualTo(12 * 60_000L + 30_000L)
    }

    @Test
    fun `horizontal drag never seeks outside episode`() {
        assertThat(calculateSwipeSeekTarget(10_000L, 1_200_000L, -1f)).isEqualTo(0L)
        assertThat(calculateSwipeSeekTarget(1_190_000L, 1_200_000L, 1f))
            .isEqualTo(1_200_000L)
    }

    @Test
    fun `step seek is clamped at beginning and end`() {
        assertThat(clampSeekPosition(-5_000L, 60_000L)).isEqualTo(0L)
        assertThat(clampSeekPosition(70_000L, 60_000L)).isEqualTo(60_000L)
    }

    @Test
    fun `vertical swipe up increases level and down decreases it`() {
        assertThat(calculateVerticalGestureLevel(0.5f, -0.25f)).isGreaterThan(0.5f)
        assertThat(calculateVerticalGestureLevel(0.5f, 0.25f)).isLessThan(0.5f)
    }

    @Test
    fun `vertical gesture is clamped to valid range`() {
        assertThat(calculateVerticalGestureLevel(0.9f, -1f)).isEqualTo(1f)
        assertThat(calculateVerticalGestureLevel(0.1f, 1f)).isEqualTo(0f)
    }

    @Test
    fun seekPreviewBucket_quantizesToFiveSecondFrames() {
        assertThat(seekPreviewBucket(0L)).isEqualTo(0L)
        assertThat(seekPreviewBucket(4_999L)).isEqualTo(0L)
        assertThat(seekPreviewBucket(5_001L)).isEqualTo(5_000L)
        assertThat(seekPreviewBucket(64_321L)).isEqualTo(60_000L)
    }

    @Test
    fun seekPreviewBucket_neverReturnsNegativeTime() {
        assertThat(seekPreviewBucket(-9_000L)).isEqualTo(0L)
    }
}
