package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerControlsTest {
    @Test
    fun scaleModeStepsForwardAndWraps() {
        assertThat(VideoScaleMode.FIT.step(1)).isEqualTo(VideoScaleMode.FILL)
        assertThat(VideoScaleMode.FILL.step(1)).isEqualTo(VideoScaleMode.ZOOM)
        assertThat(VideoScaleMode.ZOOM.step(1)).isEqualTo(VideoScaleMode.FIT)
    }

    @Test
    fun scaleModeStepsBackwardAndWraps() {
        assertThat(VideoScaleMode.FIT.step(-1)).isEqualTo(VideoScaleMode.ZOOM)
        assertThat(VideoScaleMode.ZOOM.step(-1)).isEqualTo(VideoScaleMode.FILL)
        assertThat(VideoScaleMode.FILL.step(-1)).isEqualTo(VideoScaleMode.FIT)
    }
}
