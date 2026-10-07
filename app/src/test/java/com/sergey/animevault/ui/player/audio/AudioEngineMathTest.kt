package com.sergey.animevault.ui.player.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEngineMathTest {
    @Test
    fun `optimized headroom matches cascade response across presets and sample rates`() {
        for (sampleRate in listOf(8_000, 44_100, 48_000, 96_000)) {
            for (preset in listOf("FLAT", "DIALOGUE", "BASS", "BRIGHT", "NIGHT", "CINEMA", "LOUD", "MAX")) {
                val config = DspPresets.config(preset)
                val expected = referenceBoostDb(sampleRate, config.eqBands, config.bassPercent)
                val actual = estimateMaxEqBoostDb(sampleRate, config.eqBands, config.bassPercent)
                assertThat(abs(actual - expected)).isLessThan(0.001f)
            }
        }
    }

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

    /** Original per-band dB summation retained as an independent numeric reference. */
    private fun referenceBoostDb(sampleRate: Int, bands: List<DspEqBand>, bassPercent: Float): Float {
        val coefficients = bands.map {
            BiquadDesigner.design(it.type, sampleRate, it.frequencyHz, it.q, it.gainDb)
        }.toMutableList()
        if (bassPercent > 0f) {
            coefficients += BiquadDesigner.design(DspFilterType.LOW_SHELF, sampleRate, 95f, 0.707f, bassPercent * 0.06f)
        }
        var peakDb = 0.0
        repeat(96) { index ->
            val frequency = 20.0 * (minOf(20_000.0, sampleRate * 0.45) / 20.0).pow(index / 95.0)
            val w = 2.0 * PI * frequency / sampleRate
            var sumDb = 0.0
            for (c in coefficients) {
                val nr = c.b0 + c.b1 * cos(w) + c.b2 * cos(2.0 * w)
                val ni = -c.b1 * sin(w) - c.b2 * sin(2.0 * w)
                val dr = 1.0 + c.a1 * cos(w) + c.a2 * cos(2.0 * w)
                val di = -c.a1 * sin(w) - c.a2 * sin(2.0 * w)
                val magnitude = sqrt((nr * nr + ni * ni) / (dr * dr + di * di).coerceAtLeast(1e-18))
                    .coerceAtLeast(1e-9)
                sumDb += 20.0 * ln(magnitude) / ln(10.0)
            }
            if (sumDb.isFinite()) peakDb = maxOf(peakDb, sumDb)
        }
        return peakDb.toFloat()
    }
}
