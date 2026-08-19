package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerEqualizerTest {
    @Test
    fun `dialogue preset attenuates rumble and boosts speech`() {
        assertThat(presetLevelMb(EqualizerPreset.DIALOGUE, 80)).isLessThan(0)
        assertThat(presetLevelMb(EqualizerPreset.DIALOGUE, 2_000)).isGreaterThan(0)
    }

    @Test
    fun `bass preset boosts low frequencies more than treble`() {
        val bass = presetLevelMb(EqualizerPreset.BASS, 80)
        val treble = presetLevelMb(EqualizerPreset.BASS, 8_000)

        assertThat(bass).isGreaterThan(treble)
    }

    @Test
    fun `flat preset does not alter bands`() {
        assertThat(presetLevelMb(EqualizerPreset.FLAT, 60)).isEqualTo(0)
        assertThat(presetLevelMb(EqualizerPreset.FLAT, 16_000)).isEqualTo(0)
    }
    @Test
    fun `dialogue preset adds moderate loudness without heavy bass`() {
        val tuning = presetAudioTuning(EqualizerPreset.DIALOGUE)

        assertThat(tuning.loudnessGainMb).isGreaterThan(0)
        assertThat(tuning.bassBoostStrength.toInt()).isLessThan(200)
    }

    @Test
    fun `bass preset uses stronger bass enhancement`() {
        val bass = presetAudioTuning(EqualizerPreset.BASS)
        val dialogue = presetAudioTuning(EqualizerPreset.DIALOGUE)

        assertThat(bass.bassBoostStrength).isGreaterThan(dialogue.bassBoostStrength)
    }

    @Test
    fun `off preset disables auxiliary enhancement`() {
        assertThat(presetAudioTuning(EqualizerPreset.OFF)).isEqualTo(PresetAudioTuning(0, 0))
    }

}
