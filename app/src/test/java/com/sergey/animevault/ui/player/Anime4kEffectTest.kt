package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Anime4kEffectTest {
    @Test
    fun outputSize_upscales720pTo1080pEnvelope() {
        val size = anime4kOutputSize(1280, 720)
        assertThat(size.width).isEqualTo(1920)
        assertThat(size.height).isEqualTo(1080)
    }

    @Test
    fun outputSize_keeps1080pNative() {
        val size = anime4kOutputSize(1920, 1080)
        assertThat(size.width).isEqualTo(1920)
        assertThat(size.height).isEqualTo(1080)
    }

    @Test
    fun outputSize_doesNotDownscale1440p() {
        val size = anime4kOutputSize(2560, 1440)
        assertThat(size.width).isEqualTo(2560)
        assertThat(size.height).isEqualTo(1440)
    }

    @Test
    fun outputSize_keepsAspectRatioForSd() {
        val size = anime4kOutputSize(640, 480)
        assertThat(size.width).isEqualTo(1280)
        assertThat(size.height).isEqualTo(960)
    }
}
