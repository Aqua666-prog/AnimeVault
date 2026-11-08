package com.sergey.animevault.ui.theme

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sergey.animevault.ui.preferences.*
import com.sergey.animevault.ui.settings.VaultThemePicker
import com.sergey.animevault.ui.startup.RaidenSplashScreen
import org.junit.*
import org.junit.Assert.*

class RaidenUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectionChangesThemeWithoutRestart() {
        var selected by mutableStateOf(VaultThemeMode.VAULT)
        compose.setContent {
            AnimeVaultTheme(AppearanceSettings(theme = selected, motion = VaultMotionMode.OFF)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { VaultThemePicker(selected) { selected = it } }
            }
        }
        compose.onNodeWithText("Обитель Эвтюмии").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(VaultThemeMode.EUTHYMIA, selected) }
        screenshot("euthymia")
        compose.onNodeWithText("Мусо но Хитотати").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(VaultThemeMode.MUSOU, selected) }
        screenshot("musou")
    }
    @Test fun disabledDecorationSkipsSplashAndKeepsContent() {
        var finished by mutableStateOf(false)
        compose.setContent {
            AnimeVaultTheme(AppearanceSettings(theme = VaultThemeMode.MUSOU, decorativeAnimations = false)) {
                Text("Главная готова")
                if (!finished) RaidenSplashScreen { finished = true }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(finished) }
        compose.onNodeWithText("Пропустить заставку").assertDoesNotExist()
        compose.onNodeWithText("Главная готова").assertIsDisplayed()
    }
    @Test fun settingsContentClearsHeaderAndBottomInsetAtLargeFont() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, scale)) {
                AnimeVaultTheme(AppearanceSettings(theme=VaultThemeMode.EUTHYMIA, motion=VaultMotionMode.OFF)) {
                    androidx.compose.foundation.layout.Box(Modifier
                        .then(Modifier.testTag("settings-frame"))) {
                        com.sergey.animevault.ui.settings.VaultSettingsFrame("Настройки", {}, "Назад",
                            insets=androidx.compose.foundation.layout.WindowInsets(top=androidx.compose.ui.unit.Dp(32f), bottom=androidx.compose.ui.unit.Dp(24f))) { padding ->
                            androidx.compose.foundation.lazy.LazyColumn(Modifier
                                .then(Modifier.testTag("settings-list"))
                                .then(Modifier.padding(padding))) {
                                items(30) { Text("Параметр $it: длинное описание настройки", Modifier.padding(androidx.compose.ui.unit.Dp(16f))) }
                            }
                        }
                    }
                }
            }
        }
        for (font in listOf(1f, 2f)) {
            compose.runOnIdle { scale=font }
            compose.onNodeWithTag("settings-list").performScrollToIndex(0)
            val header=compose.onNodeWithText("Настройки").fetchSemanticsNode().boundsInRoot
            val first=compose.onNodeWithText("Параметр 0: длинное описание настройки").fetchSemanticsNode().boundsInRoot
            assertTrue("First setting must start below the header at font $font", first.top >= header.bottom)
            compose.onNodeWithTag("settings-list").performScrollToIndex(29)
            compose.onNodeWithText("Параметр 29: длинное описание настройки").assertIsDisplayed()
            val last=compose.onNodeWithText("Параметр 29: длинное описание настройки").fetchSemanticsNode().boundsInRoot
            val frame=compose.onNodeWithTag("settings-frame").fetchSemanticsNode().boundsInRoot
            assertTrue("Bottom inset must leave the last setting reachable", last.bottom < frame.bottom)
        }
        screenshot("settings-large-font")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "raiden-preview")
        check(folder.mkdirs() || folder.isDirectory)
        val file = java.io.File(folder, "$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val command = "mkdir -p /data/local/tmp/animevault-raiden && cp ${file.absolutePath} /data/local/tmp/animevault-raiden/$name.png"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
