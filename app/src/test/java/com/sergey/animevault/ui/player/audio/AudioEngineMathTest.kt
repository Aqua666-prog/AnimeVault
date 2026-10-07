package com.sergey.animevault.ui.player.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class AudioEngineMathTest {
    @Test
    fun `flat eq has effectively zero estimated boost`() {
        assertThat(estimateMaxEqBoostDb(48_000, flatEqBands(), 0f)).isLessThan(0.05f)
    }

    @Test
    fun `positive eq produces positive headroom requirement`() {
        val bands = flatEqBands().mapIndexed { index, band ->
            if (index == 6) band.copy(gainDb = 6f) else band
        }
        assertThat(estimateMaxEqBoostDb(48_000, bands, 0f)).isGreaterThan(4f)
    }

    @Test
    fun `biquad stays finite for strong peak boost`() {
        val filter = SmoothBiquad()
        filter.setCoefficients(BiquadDesigner.design(DspFilterType.PEAK, 48_000, 2_000f, 1f, 12f), 0)
        repeat(100_000) { index ->
            val sample = if (index == 0) 1f else 0f
            assertThat(filter.process(sample).isFinite()).isTrue()
        }
    }

    @Test
    fun `max preset is louder and more dynamic controlled than flat`() {
        val flat = DspPresets.config("FLAT")
        val max = DspPresets.config("MAX")
        assertThat(max.loudnessPercent).isGreaterThan(flat.loudnessPercent)
        assertThat(max.dynamicsAmount).isGreaterThan(flat.dynamicsAmount)
        assertThat(max.limiter.ceilingDb).isAtMost(-0.1f)
    }

    @Test
    fun `linked compressor preserves identical stereo channels`() {
        val compressor = StereoLinkedCompressor().also {
            it.configure(48_000)
            it.setParameters(-20f, 4f, 5f, 100f, 6f, 2f)
        }
        repeat(5_000) {
            compressor.process(0.8f, 0.8f)
            assertThat(abs(compressor.outLeft - compressor.outRight)).isLessThan(1e-6f)
        }
    }

    @Test
    fun `three band dynamics remains finite for one second of stereo audio`() {
        val dynamics = ThreeBandDynamics().also { it.configure(48_000, 0.94f) }
        repeat(48_000) { frame ->
            val sample = if (frame % 127 == 0) 1.1f else 0.55f
            dynamics.process(sample, -sample)
            assertThat(dynamics.outLeft.isFinite()).isTrue()
            assertThat(dynamics.outRight.isFinite()).isTrue()
        }
    }
}
