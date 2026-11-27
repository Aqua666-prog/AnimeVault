package com.sergey.animevault.discovery

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w393dp-h852dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun journal() = captureAll(Concept.Journal)
    @Test fun session() = captureAll(Concept.Session)
    @Test fun index() = captureAll(Concept.Index)

    @Test
    @Config(qualifiers = "ru-rRU-w852dp-h393dp-land-mdpi")
    fun landscapePlayer() {
        val concept = mutableStateOf(Concept.Journal)
        compose.setContent { DiscoveryPrototype(concept.value, Screen.Player, motionEnabled = false) }
        for (direction in Concept.entries) {
            compose.runOnIdle { concept.value = direction }
            capture(direction, "player-landscape")
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h720dp-port-mdpi")
    fun compactLargeText() {
        val concept = mutableStateOf(Concept.Journal)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
                DiscoveryPrototype(concept.value, Screen.Catalog, motionEnabled = false)
            }
        }
        for (direction in Concept.entries) {
            compose.runOnIdle { concept.value = direction }
            capture(direction, "catalog-large-text")
        }
    }

    @Test
    fun journalBackPreservesSearchAndSavedTitle() {
        compose.setContent { DiscoveryPrototype(Concept.Journal, Screen.Catalog, motionEnabled = false) }
        compose.onNodeWithContentDescription("Поиск аниме").performTextInput("прилив")
        compose.onNodeWithText("Стеклянный прилив").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("В библиотеке").assertExists()
        compose.onNodeWithText("‹ Назад").performClick()
        compose.onNodeWithText("Сигнал над морем").assertDoesNotExist()
        compose.onNodeWithText("Стеклянный прилив").assertExists()
        compose.onNodeWithText("Библиотека").performClick()
        compose.onNodeWithText("Стеклянный прилив").assertExists()
    }

    @Test
    fun sessionSearchToPlayer() {
        compose.setContent { DiscoveryPrototype(Concept.Session, Screen.Catalog, motionEnabled = false) }
        compose.onNodeWithContentDescription("Поиск аниме").performTextInput("орбита")
        compose.onNodeWithText("Тихая орбита").assertExists()
        compose.onNodeWithText("Сигнал над морем").assertDoesNotExist()
        compose.onNodeWithText("Тихая орбита").performClick()
        compose.onNodeWithText("▶  Продолжить серию 4").performClick()
        compose.onNodeWithText("Карта ветров").assertExists()
        compose.onNodeWithText("Смотреть").performClick()
        compose.onNodeWithText("Пауза").assertExists()
        compose.onNodeWithText("Звук и субтитры").performClick()
        compose.onNodeWithText("Субтитры: вкл.").performClick()
        compose.onNodeWithText("Субтитры: выкл.").assertExists()
        compose.onNodeWithText("‹ Назад").performClick()
        compose.onNodeWithText("Звук и субтитры").assertExists()
        compose.onNodeWithText("‹ Назад").performClick()
        compose.onNodeWithText("▶  Продолжить серию 4").assertExists()
    }

    @Test
    fun indexDownloadPauseAndResume() {
        compose.setContent { DiscoveryPrototype(Concept.Index, motionEnabled = false) }
        compose.onNodeWithText("Офлайн").performClick()
        compose.onNodeWithText("Пауза").performClick()
        compose.onNodeWithText("СЕРИЯ 05 · ПАУЗА · 64%").assertExists()
        compose.onNodeWithText("Возобновить").performClick()
        compose.onNodeWithText("СЕРИЯ 05 · ЗАГРУЖАЕТСЯ · 64%")
            .assertTextContains("СЕРИЯ 05 · ЗАГРУЖАЕТСЯ · 64%")
    }

    private fun captureAll(concept: Concept) {
        val screen = mutableStateOf(Screen.Home)
        compose.setContent { DiscoveryPrototype(concept, screen.value, motionEnabled = false) }
        for (target in Screen.entries) {
            compose.runOnIdle { screen.value = target }
            capture(concept, target.name.lowercase())
        }
    }

    private fun capture(concept: Concept, name: String) {
        val directory = checkNotNull(System.getProperty("animevault.discovery.snapshots"))
        val file = File(directory, "${concept.folder}/screenshots/$name.png")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }
}
