package com.sergey.animevault.ui.player

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerOrientationRestoreTest {
    @Test
    fun restoreReturnsPreviousActivityPolicy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previous = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                restorePlayerOrientation(activity, previous)
                assertEquals(previous, activity.requestedOrientation)
            }
        }
    }
}
