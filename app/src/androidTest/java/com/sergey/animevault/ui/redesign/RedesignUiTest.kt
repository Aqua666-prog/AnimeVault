package com.sergey.animevault.ui.redesign

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.sergey.animevault.ui.components.*
import com.sergey.animevault.ui.downloads.DownloadsScreen
import com.sergey.animevault.ui.home.*
import com.sergey.animevault.ui.library.*
import com.sergey.animevault.ui.navigation.*
import com.sergey.animevault.ui.online.*
import com.sergey.animevault.ui.preferences.*
import com.sergey.animevault.ui.preview.DownloadPreviewCases
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Presentation fixtures exercise real Compose surfaces; they do not claim network/player QA. */
class RedesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val searchLabel get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(com.sergey.animevault.R.string.search_field_label)

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                com.sergey.animevault.ui.theme.AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.OFF)) {
                    Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { block() } }
                }
            }
        }
    }

    @Test fun navigationRetainsSelectionAndAllDestinationsAtLargeFont() {
        var route by mutableStateOf("home")
        val items = listOf("home" to "Главная", "online" to "Каталог", "library" to "Библиотека",
            "downloads" to "Загрузки", "settings" to "Настройки").map { (id, label) -> VaultRootNavItem(id, label, Icons.Outlined.Home) }
        content(2f) { Box(Modifier.width(320.dp)) { VaultBottomNavigation(items, route) { route = it } } }
        items.forEach { item ->
            compose.onNodeWithText(item.label).assertIsDisplayed()
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(item.label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue("Complete label: ${item.label}", layout.isNotEmpty() && layout.none { it.hasVisualOverflow })
        }
        compose.onNodeWithText("Загрузки").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("downloads", route) }
        screenshot("navigation-320-font2")
    }

    @Test fun shortRailKeepsLastDestinationReachable() {
        var route by mutableStateOf("0")
        val items = (0..4).map { VaultRootNavItem("$it", "Раздел $it", Icons.Outlined.Home) }
        content(1.5f) { Box(Modifier.height(240.dp)) { VaultNavigationRail(items, route) { route = it } } }
        compose.onNodeWithText("Раздел 4").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("4", route) }
    }

    @Test fun populatedSearchRetainsNameAndClearAction() {
        var query by mutableStateOf("Путешествие")
        content { VaultSearchField(query, { query = it }, "Название или сезон", Modifier.padding(16.dp)) }
        compose.onNodeWithContentDescription(searchLabel).assert(hasSetTextAction())
        compose.onNodeWithContentDescription("Очистить").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test fun listSheetCanReachLastStatusAtLargeFont() {
        var selected: VaultTitleList? = null
        content(2f) {
            VaultTitleListPicker(VaultTitleList.WATCHING, false, {}, { selected = it }, {})
        }
        compose.onNodeWithText("Брошено", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(VaultTitleList.DROPPED, selected) }
        screenshot("list-picker-font2")
    }

    @Test fun resumeAppearsBeforeDiscoveryAndOpensExactEpisode() {
        var episode: String? = null
        val state = HomePreviewCases().values.drop(1).first()
        content {
            HomeScreen(state, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _, id -> episode = id }, {}, {}, {}, {})
        }
        val resume = compose.onNodeWithContentDescription("Продолжить За облаками", substring = true)
        resume.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("ep7", episode) }
        val resumeHeading = compose.onNodeWithText("Продолжить просмотр").fetchSemanticsNode().boundsInRoot
        val featureY = compose.onNodeWithText("ПРОДОЛЖИТЬ ИСТОРИЮ").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Resume precedes discovery", resumeHeading.top < featureY)
        compose.onNodeWithText("Продолжить просмотр").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTrue(resumeHeading.top >= 0f)
        screenshot("home-resume")
    }

    @Test fun libraryListsRemainAvailableBeyondPrimaryTabs() {
        var savedTab = ""
        content {
            LibraryHubScreen(LibraryHubUiState(loading = false), { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {},
                initialTabName = "CONTINUE", onTabSelected = { savedTab = it })
        }
        compose.onNodeWithText("Списки · 0").performClick()
        compose.onNodeWithText("Брошено").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("DROPPED", savedTab) }
        screenshot("library-lists")
    }

    @Test fun downloadPauseAndRemovalConfirmationUseOriginalIds() {
        var paused: String? = null
        var removed: String? = null
        val active = DownloadPreviewCases().values.drop(1).first().first { it.status == com.sergey.animevault.data.download.DownloadStatus.DOWNLOADING }
        content { DownloadsScreen(listOf(active), {}, { paused = it }, {}, { removed = it }) }
        compose.onNodeWithText("Пауза").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(active.id, paused) }
        compose.onNodeWithContentDescription("Удалить офлайн-копию").performClick()
        compose.onNodeWithText("Удалить офлайн-копию?").assertIsDisplayed()
        compose.runOnIdle { assertNull(removed) }
        screenshot("download-confirmation")
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(active.id, removed) }
    }

    @Test fun headingAndLongTitleRemainVisibleAtLargeFont() {
        content(2f) {
            Column(Modifier.width(320.dp)) {
                VaultTopAppBar(title = { VaultScreenHeading("Загрузки", "Смотрите без сети") },
                    navigationIcon = { VaultTopBarAction(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", {}) },
                    actions = { repeat(3) { VaultTopBarAction(Icons.Outlined.MoreVert, "Действие $it", {}) } })
                VaultPosterCard("Последнее путешествие к далёким звёздам", null, {}, Modifier.width(220.dp))
            }
        }
        compose.onNodeWithText("Загрузки").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Последнее путешествие к далёким звёздам").assertIsDisplayed()
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Загрузки").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertTrue("Header text must fit", layout.isNotEmpty() && layout.none { it.hasVisualOverflow })
        screenshot("header-font2")
    }

    @Test fun catalogFiltersOpenAsSeparateTaskAndKeepSearchAvailable() {
        val state = com.sergey.animevault.ui.preview.CatalogPreviewCases().values.first()
        content {
            OnlineCatalogScreen(state, onQueryChange = {}, onRefresh = {}, onRefreshProviderHealth = {},
                onLoadMore = {}, onSelectProvider = {}, onSelectGenre = {}, onSetAdvancedFilter = {},
                onSavePreset = {}, onApplyPreset = {}, onDeletePreset = {}, onSelectCollection = {},
                onSelectSort = {}, onSelectYear = {}, onSelectType = {}, onSelectRating = {},
                onSelectStatus = {}, onSelectEpisodeFilter = {}, onToggleLayout = {}, onResetDiscovery = {},
                onRemoveSearchHistory = {}, onClearSearchHistory = {}, onOpenSettings = {}, onOpenLibrary = {},
                onOpenCollections = {}, onOpenRandomTitle = {}, onSearchTenraiTitle = {}, onOpenTitle = {},
                onPlayEpisode = { _, _, _ -> })
        }
        compose.onNodeWithContentDescription(searchLabel).assertIsDisplayed()
        screenshot("catalog")
        compose.onNodeWithContentDescription("Фильтры").performClick()
        compose.onNodeWithText("Показать результаты").performScrollTo().performClick()
        compose.onNodeWithContentDescription(searchLabel).assertIsDisplayed()
    }

    @Test fun titleKeepsExactEpisodeAndPersonalActionsOnAbout() {
        var played: String? = null
        val episode = com.sergey.animevault.data.online.OnlineEpisode("test", "episode-7", "release", 7.0,
            "Дорога к далёкому морю", null, 1_440_000L, 7.0, emptyList(), sourceRef = "fixture-only")
        val release = com.sergey.animevault.data.online.OnlineReleaseDetails("test", "Тестовый источник", "release", "release",
            "Путешествие за облаками", null, null, 2026, "TV", "Осень", 1,
            "История о путешествии, дружбе и возвращении домой.", null, listOf("Приключения"), false, false, listOf(episode))
        content {
            OnlineTitleScreen(OnlineTitleUiState(isLoading = false, release = release, continueEpisodeId = episode.id),
                onBack = {}, onRetry = {}, onRetryThemes = {}, onRetryExtras = {},
                onPlayEpisode = { played = it }, onOpenLocalTitle = {}, onOpenDownloads = {},
                onSelectTranslation = {}, onToggleFavorite = {}, onDownloadEpisode = {},
                onPauseDownload = {}, onResumeDownload = {}, onRemoveDownload = {})
        }
        screenshot("title")
        compose.onNodeWithText("Моя оценка").assertDoesNotExist()
        compose.onNodeWithText("Серия 7").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("episode-7", played) }
        compose.onNodeWithText("Описание", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Моя оценка").performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "redesign-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
