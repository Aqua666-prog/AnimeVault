package com.sergey.animevault.ui.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSkipTest {
    @Test
    fun `timecode parser supports minutes seconds and hours`() {
        assertThat(parsePlayerTimecode("01:30")).isEqualTo(90_000L)
        assertThat(parsePlayerTimecode("1:02:03")).isEqualTo(3_723_000L)
        assertThat(parsePlayerTimecode("90")).isEqualTo(90_000L)
    }

    @Test
    fun `timecode parser rejects invalid clock values`() {
        assertThat(parsePlayerTimecode("1:75")).isNull()
        assertThat(parsePlayerTimecode("1:60:00")).isNull()
        assertThat(parsePlayerTimecode("abc")).isNull()
    }

    @Test
    fun `opening is skipped only while position is inside configured range`() {
        val settings = PlayerSkipSettings(
            autoSkipOpening = true,
            openingStartMs = 60_000L,
            openingEndMs = 150_000L,
        )

        assertThat(autoSkipDecision(settings, 59_999L, 1_400_000L)).isNull()
        assertThat(autoSkipDecision(settings, 90_000L, 1_400_000L)).isEqualTo(
            AutoSkipDecision(AutoSkipSegment.OPENING, 150_000L),
        )
        assertThat(autoSkipDecision(settings, 150_000L, 1_400_000L)).isNull()
    }

    @Test
    fun `skip target is clamped to known media duration`() {
        val settings = PlayerSkipSettings(
            autoSkipEnding = true,
            endingStartMs = 1_300_000L,
            endingEndMs = 1_500_000L,
        )

        assertThat(autoSkipDecision(settings, 1_350_000L, 1_400_000L)).isEqualTo(
            AutoSkipDecision(AutoSkipSegment.ENDING, 1_400_000L),
        )
    }

    @Test
    fun `disabled skip never moves playback`() {
        val settings = PlayerSkipSettings(
            openingStartMs = 60_000L,
            openingEndMs = 150_000L,
            endingStartMs = 1_200_000L,
            endingEndMs = 1_300_000L,
        )

        assertThat(autoSkipDecision(settings, 90_000L, 1_400_000L)).isNull()
        assertThat(autoSkipDecision(settings, 1_250_000L, 1_400_000L)).isNull()
    }
}
