package com.sergey.animevault.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sergey.animevault.AnimeVaultApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkSetup {
    @Test
    fun prepareOnboardedInstallation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AnimeVaultApplication>()
        val onboarding = app.container.onboardingStore
        onboarding.complete()
        assertFalse(onboarding.shouldShow())
    }
}
