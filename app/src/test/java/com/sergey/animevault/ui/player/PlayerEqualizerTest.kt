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
}
