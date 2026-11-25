package com.sergey.animevault.visual

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.sergey.animevault.data.metadata.VaultAiringEpisode
import com.sergey.animevault.ui.calendar.AnimeCalendarContent
import com.sergey.animevault.ui.downloads.DownloadsScreen
import com.sergey.animevault.ui.home.HomePreviewCases
import com.sergey.animevault.ui.home.HomeScreen
import com.sergey.animevault.ui.library.LibraryHubScreen
import com.sergey.animevault.ui.online.OnlineCatalogScreen
import com.sergey.animevault.ui.online.OnlineCatalogUiState
import com.sergey.animevault.ui.player.PlayerNowPlayingBar
import com.sergey.animevault.ui.player.PlayerPreviewSnapshots
import com.sergey.animevault.ui.player.PlayerSkipSettings
import com.sergey.animevault.ui.player.PlayerTimelineContent
import com.sergey.animevault.ui.player.PlayerTransportContent
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.GraphicsPreset
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.preview.CatalogPreviewCases
import com.sergey.animevault.ui.preview.DownloadPreviewCases
import com.sergey.animevault.ui.preview.LibraryPreviewCases
import com.sergey.animevault.ui.settings.GraphicsPresetSettingsCard
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import com.sergey.animevault.ui.title.TitlePreviewModels
import com.sergey.animevault.ui.title.UnifiedTitleOverview
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w393dp-h852dp-mdpi")
class AscensionVisualTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun home() = snapshot("home") {
        HomeScreen(
            HomePreviewCases().values.elementAt(1),
            onOpenOffline = {}, onOpenOnline = {}, onOpenClips = {},
            onOpenSettings = {}, onOpenStatistics = {}, onOpenLocalTitle = {},
            onPlayLocalEpisode = {}, onOpenOnlineTitle = { _, _ -> },
            onPlayOnlineEpisode = { _, _, _ -> }, onDiscoverTitle = {},
            onRefresh = {}, onNextFeatured = {}, onToggleFavorite = {},
        )
    }

    @Test
    fun catalog() = snapshot("catalog", preset = GraphicsPreset.PERFORMANCE) {
        Catalog(CatalogPreviewCases().values.first())
    }

    @Test
    fun search() = snapshot("search", theme = VaultThemeMode.MIDNIGHT) {
        Catalog(CatalogPreviewCases().values.first().copy(query = "облаками"))
    }

    @Test
    fun title() = snapshot("title", theme = VaultThemeMode.EUTHYMIA, preset = GraphicsPreset.CINEMATIC_ULTRA) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            UnifiedTitleOverview(TitlePreviewModels().values.first(), "Продолжить", {},
                onOpenLocal = {}, onOpenOnline = { _, _ -> })
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w840dp-h620dp-mdpi")
    fun libraryWide() = snapshot("library-wide", theme = VaultThemeMode.MUSOU) {
        LibraryHubScreen(LibraryPreviewCases().values.elementAt(1), { _, _ -> }, {}, { _, _ -> },
            {}, {}, {}, {}, initialTabName = "ALL")
    }

    @Test
    fun downloads() = snapshot("downloads", theme = VaultThemeMode.OLED) {
        DownloadsScreen(DownloadPreviewCases().values.elementAt(1), {}, {}, {}, {})
    }

    @Test
    fun calendar() = snapshot("calendar") {
        val start = 1_798_761_600L
        AnimeCalendarContent(
            bounds = start to start + 7 * 86_400,
            entries = listOf(
                VaultAiringEpisode(1, "Город за облаками", 7, start + 38_400, null, countryCode = "JP"),
                VaultAiringEpisode(2, "Тихий океан", 2, start + 93_600, null, countryCode = "CN"),
            ),
            country = null, loading = false, translating = false, error = null, nowSeconds = start,
            onSelectCountry = {}, onShiftWeek = {}, onRetry = {}, onBack = {},
            onSearchAnime = {}, onOpenAniList = {},
        )
    }

    @Test
    fun settingsLargeFont() = snapshot("settings-large-font", fontScale = 2f) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            GraphicsPresetSettingsCard(GraphicsPreset.BALANCED, false) {}
        }
    }

    @Test
    fun settingsDynamic() = snapshot("settings-dynamic", theme = VaultThemeMode.DYNAMIC, preset = GraphicsPreset.CINEMATIC_ULTRA) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            GraphicsPresetSettingsCard(GraphicsPreset.CINEMATIC_ULTRA, false) {}
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w840dp-h360dp-mdpi")
    fun playerLandscape() = snapshot("player-landscape", theme = VaultThemeMode.MUSOU, preset = GraphicsPreset.CINEMATIC_ULTRA) {
        val player = PlayerPreviewSnapshots().values.elementAt(1)
        Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerNowPlayingBar("Город за облаками", "Серия 7 · HLS")
            PlayerTransportContent(player, {}, {}, {})
            PlayerTimelineContent(player, PlayerSkipSettings(openingEndMs = 90_000), false, 0, {}, {})
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
    fun catalogErrorCompact() {
        var retried = false
        snapshot("catalog-error-compact", fontScale = 2f) {
            Catalog(CatalogPreviewCases().values.elementAt(2), onRefresh = { retried = true })
        }
        compose.onNodeWithText("Повторить").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertTrue(retried)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
    fun catalogLoadingCompact() = snapshot("catalog-loading-compact", preset = GraphicsPreset.PERFORMANCE) {
        Catalog(CatalogPreviewCases().values.last())
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
    fun titleLargeFont() = snapshot("title-large-font", theme = VaultThemeMode.EUTHYMIA, fontScale = 2f) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            UnifiedTitleOverview(TitlePreviewModels().values.elementAt(2), "Смотреть", {},
                onOpenLocal = {}, onOpenOnline = { _, _ -> })
        }
    }

    @Test
    fun libraryEmpty() = snapshot("library-empty", theme = VaultThemeMode.OLED) {
        LibraryHubScreen(LibraryPreviewCases().values.first(), { _, _ -> }, {}, { _, _ -> },
            {}, {}, {}, {}, initialTabName = "ALL")
    }

    private fun snapshot(
        name: String,
        theme: VaultThemeMode = VaultThemeMode.VAULT,
        preset: GraphicsPreset = GraphicsPreset.BALANCED,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(deviceDensity, fontScale)) {
                AnimeVaultTheme(AppearanceSettings(theme = theme, motion = VaultMotionMode.OFF), preset) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        content = content,
                    )
                }
            }
        }
        val directory = checkNotNull(System.getProperty("animevault.visual.snapshots"))
        compose.onRoot().captureRoboImage(File(directory, "$name.png"))
    }
}

@Composable
private fun Catalog(state: OnlineCatalogUiState, onRefresh: () -> Unit = {}) {
    OnlineCatalogScreen(state, onQueryChange = {}, onRefresh = onRefresh, onRefreshProviderHealth = {},
        onLoadMore = {}, onSelectProvider = {}, onSelectGenre = {}, onSetAdvancedFilter = {},
        onSavePreset = {}, onApplyPreset = {}, onDeletePreset = {}, onSelectCollection = {},
        onSelectSort = {}, onSelectYear = {}, onSelectType = {}, onSelectRating = {},
        onSelectStatus = {}, onSelectEpisodeFilter = {}, onToggleLayout = {}, onResetDiscovery = {},
        onRemoveSearchHistory = {}, onClearSearchHistory = {}, onOpenSettings = {}, onOpenLibrary = {},
        onOpenCollections = {}, onOpenRandomTitle = {}, onSearchTenraiTitle = {}, onOpenTitle = {},
        onPlayEpisode = { _, _, _ -> })
}
