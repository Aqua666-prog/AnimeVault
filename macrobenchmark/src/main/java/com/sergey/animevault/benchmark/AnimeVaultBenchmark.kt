package com.sergey.animevault.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.sergey.animevault"
private const val UI_TIMEOUT_MS = 10_000L

@RunWith(AndroidJUnit4::class)
class AnimeVaultBenchmark {
    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartup() = startup(StartupMode.COLD)

    @Test
    fun warmStartup() = startup(StartupMode.WARM)

    @Test
    fun hotStartup() = startup(StartupMode.HOT)

    @Test
    fun mainSectionNavigation() = benchmark.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        iterations = 8,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForNavigation()
        },
    ) {
        navigateMainSections()
    }

    private fun startup(mode: StartupMode) = benchmark.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForNavigation()
    }
}

@RunWith(AndroidJUnit4::class)
class AnimeVaultBaselineProfile {
    @get:Rule
    val baseline = BaselineProfileRule()

    @Test
    fun startup() = baseline.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForNavigation()
    }

    @Test
    fun mainNavigation() = baseline.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        waitForNavigation()
        navigateMainSections()
    }
}

private fun MacrobenchmarkScope.waitForNavigation() {
    checkNotNull(device.wait(Until.findObject(By.res("vault-route-home")), UI_TIMEOUT_MS)) {
        "Open AnimeVault before measuring, finish onboarding, and dismiss any permission dialogs."
    }
}

private fun MacrobenchmarkScope.navigateMainSections() {
    for (route in listOf("online", "online-library", "downloads", "settings", "home")) {
        val tab = checkNotNull(device.wait(Until.findObject(By.res("vault-route-$route")), UI_TIMEOUT_MS))
        tab.click()
        check(device.wait(Until.hasObject(By.res("vault-route-$route").selected(true)), UI_TIMEOUT_MS)) {
            "Navigation did not select $route"
        }
    }
}
