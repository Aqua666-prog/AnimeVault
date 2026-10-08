package com.sergey.animevault.ui.startup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the actual frame-clock effect: waiting, completion, reduced motion and cancellation. */
@RunWith(AndroidJUnit4::class)
class SignatureIntroTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun controlFrameClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun fullIntroFinishesOnceAfterItsLastFrame() {
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(1f, { finishes++ }) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(0, finishes) }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { assertEquals(1, finishes) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun disabledAnimationsSkipEvenWhenSystemSplashHasNotExited() {
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(0f, { finishes++ }, startAnimation = false) }
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun reducedMotionCompletesWithinHalfASecond() {
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(.45f, { finishes++ }) }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun disablingMotionDuringIntroFinishesWithoutWaitingForTimeline() {
        val motion = mutableStateOf(1f)
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(motion.value, { finishes++ }) }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { motion.value = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, finishes) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun splashWaitDoesNotConsumeVisibleAnimationTime() {
        val ready = mutableStateOf(false)
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(1f, { finishes++ }, startAnimation = ready.value) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(0, finishes)
            ready.value = true
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(0, finishes) }
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun missingSplashCallbackCannotKeepIntroForever() {
        var finishes = 0
        compose.setContent { AnimeVaultLaunchIntro(1f, { finishes++ }, startAnimation = false) }
        compose.mainClock.advanceTimeBy(4_200)
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun removingIntroCancelsItsCompletionCallback() {
        val visible = mutableStateOf(true)
        var finishes = 0
        compose.setContent {
            if (visible.value) AnimeVaultLaunchIntro(1f, { finishes++ })
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(4_200)
        compose.runOnIdle { assertEquals(0, finishes) }
    }
}
