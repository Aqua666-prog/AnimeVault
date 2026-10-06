package com.sergey.animevault.ui.player.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioEngineConfigTest {
    @Test
    fun `normalization clamps unsafe values`() {
        val config = DspConfig(
            inputGainDb = 99f,
            loudnessPercent = 999f,
            bassPercent = -50f,
            limiter = DspLimiterSettings(ceilingDb = 5f, lookAheadMs = 100f, releaseMs = 1f, oversampling = 99),
        ).normalized()

        assertThat(config.inputGainDb).isEqualTo(6f)
        assertThat(config.loudnessPercent).isEqualTo(100f)
        assertThat(config.bassPercent).isEqualTo(0f)
        assertThat(config.limiter.ceilingDb).isEqualTo(-0.1f)
        assertThat(config.limiter.lookAheadMs).isEqualTo(12f)
        assertThat(config.limiter.releaseMs).isEqualTo(30f)
        assertThat(config.limiter.oversampling).isEqualTo(4)
    }

    @Test
    fun `legacy preset names remain supported`() {
        listOf("OFF", "FLAT", "DIALOGUE", "BASS", "BRIGHT", "NIGHT").forEach { name ->
            assertThat(DspPresets.config(name).eqBands).hasSize(10)
        }
    }

    @Test
    fun `loud and max keep true peak ceiling below zero dbfs`() {
        assertThat(DspPresets.config("LOUD").limiter.ceilingDb).isLessThan(0f)
        assertThat(DspPresets.config("MAX").limiter.ceilingDb).isLessThan(0f)
    }
}
