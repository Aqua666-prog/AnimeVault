package com.sergey.animevault.ui.theme

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
