package com.sergey.animevault.data.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackStallDetectorTest {
    @Test
    fun `continuous progress never stalls`() {
        val detector = PlaybackStallDetector(timeoutMs = 5_000L, minimumProgressMs = 250L)

        assertThat(detector.observe(0L, 0L, true)).isFalse()
        assertThat(detector.observe(2_000L, 2_000L, true)).isFalse()
        assertThat(detector.observe(5_000L, 5_000L, true)).isFalse()
        assertThat(detector.observe(9_000L, 9_000L, true)).isFalse()
    }

    @Test
    fun `buffering without timeline movement signals once`() {
        val detector = PlaybackStallDetector(timeoutMs = 5_000L, minimumProgressMs = 250L)

        assertThat(detector.observe(0L, 10_000L, true)).isFalse()
        assertThat(detector.observe(4_999L, 10_000L, true)).isFalse()
        assertThat(detector.observe(5_000L, 10_000L, true)).isTrue()
        assertThat(detector.observe(7_000L, 10_000L, true)).isFalse()
    }

    @Test
    fun `pause resets the watchdog`() {
        val detector = PlaybackStallDetector(timeoutMs = 5_000L, minimumProgressMs = 250L)

        detector.observe(0L, 10_000L, true)
        assertThat(detector.observe(4_500L, 10_000L, false)).isFalse()
        assertThat(detector.observe(8_000L, 10_000L, true)).isFalse()
        assertThat(detector.observe(13_000L, 10_000L, true)).isTrue()
    }

    @Test
    fun `seek backwards starts a new progress window`() {
        val detector = PlaybackStallDetector(timeoutMs = 5_000L, minimumProgressMs = 250L)

        detector.observe(0L, 20_000L, true)
        assertThat(detector.observe(4_000L, 5_000L, true)).isFalse()
        assertThat(detector.observe(8_999L, 5_000L, true)).isFalse()
        assertThat(detector.observe(9_000L, 5_000L, true)).isTrue()
    }
}
