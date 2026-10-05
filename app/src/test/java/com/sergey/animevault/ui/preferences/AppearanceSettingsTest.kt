package com.sergey.animevault.ui.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaults_keepVaultIdentity() {
        val settings = AppearanceSettings()

        assertThat(settings.theme).isEqualTo(VaultThemeMode.VAULT)
        assertThat(settings.accent).isEqualTo(VaultAccentMode.VIOLET)
        assertThat(settings.blurEnabled).isFalse()
        assertThat(settings.motion).isEqualTo(VaultMotionMode.FULL)
    }

    @Test
    fun motionLevels_reduceDecorativeDuration() {
        assertThat(VaultMotionMode.FULL.durationScale).isGreaterThan(VaultMotionMode.REDUCED.durationScale)
        assertThat(VaultMotionMode.REDUCED.durationScale).isGreaterThan(VaultMotionMode.MINIMAL.durationScale)
        assertThat(VaultMotionMode.MINIMAL.durationScale).isGreaterThan(0f)
    }

    @Test
    fun disabledMotion_staysDisabledWhenReducedMotionIsEnabled() {
        assertThat(AppearanceSettings(motion = VaultMotionMode.OFF, reducedMotion = true).effectiveMotion)
            .isEqualTo(VaultMotionMode.OFF)
        assertThat(VaultMotionMode.OFF.durationScale).isEqualTo(0f)
    }

    @Test
    fun reducedMotion_andLegacyMinimalModeUseReducedTransitions() {
        assertThat(AppearanceSettings(reducedMotion = true).effectiveMotion).isEqualTo(VaultMotionMode.REDUCED)
        assertThat(AppearanceSettings(motion = VaultMotionMode.MINIMAL).effectiveMotion).isEqualTo(VaultMotionMode.REDUCED)
    }

    @Test
    fun playbackDefaults_matchCurrentPlayerBehaviour() {
        val defaults = PlaybackDefaults()

        assertThat(defaults.speed).isEqualTo(1f)
        assertThat(defaults.videoScale).isEqualTo(DefaultVideoScale.FIT)
        assertThat(defaults.nextEpisode).isEqualTo(DefaultNextEpisode.COUNTDOWN)
        assertThat(defaults.equalizer).isEqualTo(DefaultEqualizer.OFF)
        assertThat(defaults.subtitlesEnabled).isTrue()
    }
}
