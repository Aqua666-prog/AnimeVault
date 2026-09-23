package com.sergey.animevault.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSleepTimerTest {
    @Test
    fun timedSleepPausesOnlyAfterDeadline() {
        val state = startSleepTimer(SleepTimerMode.MINUTES_15, nowMs = 1_000L)
        assertFalse(shouldSleepTimerPause(state, 900_000L))
        assertTrue(shouldSleepTimerPause(state, 901_000L))
    }

    @Test
    fun episodeModeOnlyPausesAtEpisodeEnd() {
        val state = startSleepTimer(SleepTimerMode.END_OF_EPISODE, nowMs = 0L)
        assertFalse(shouldSleepTimerPause(state, 999_999L, episodeEnded = false))
        assertTrue(shouldSleepTimerPause(state, 999_999L, episodeEnded = true))
    }
}
