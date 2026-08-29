package com.sergey.animevault.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOrientationTest {
    @Test
    fun `portrait is rotated to landscape without sensor mode`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedPlayerOrientation(Configuration.ORIENTATION_PORTRAIT),
        )
    }

    @Test
    fun `landscape is rotated back to portrait without sensor mode`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedPlayerOrientation(Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun `undefined orientation defaults to landscape`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedPlayerOrientation(Configuration.ORIENTATION_UNDEFINED),
        )
    }
}
