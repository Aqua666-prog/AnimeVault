package com.sergey.animevault.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultTitleList
import com.sergey.animevault.ui.library.LibraryHubScreen
import com.sergey.animevault.ui.library.LibraryHubItem
import com.sergey.animevault.ui.library.LibraryHubUiState
import com.sergey.animevault.ui.home.HomeContinueItem
import com.sergey.animevault.ui.online.OnlineCatalogScreen
import com.sergey.animevault.ui.online.OnlineCatalogUiState
import com.sergey.animevault.ui.online.CatalogAdvancedControls
import com.sergey.animevault.ui.preferences.CatalogAdvancedFilter
import com.sergey.animevault.ui.downloads.DownloadsScreen
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.OnlineReleaseCard

class LibraryPreviewCases : PreviewParameterProvider<LibraryHubUiState> {
    override val values = sequenceOf(
        LibraryHubUiState(loading = false),
        LibraryHubUiState(items = (1..80).map { index ->
            LibraryHubItem("local:$index", "Длинное название истории за облаками $index", null,
                "12 серий · на устройстве", index % 3 == 0, VaultTitleList.WATCHING,
                rating = 4, localId = index.toLong())
        }, continueWatching = listOf(HomeContinueItem.Local(1L, 1L, "За облаками", null,
            7.0, 1, 600_000L, 1_440_000L, 1L)), loading = false),
        LibraryHubUiState(),
    )
}

@Preview(name = "Library 320", widthDp = 320, heightDp = 1000)
@Preview(name = "Library 600", widthDp = 600, heightDp = 1000)
@Preview(name = "Library 840", widthDp = 840, heightDp = 1000)
@Preview(name = "Library font 2", widthDp = 360, heightDp = 1200, fontScale = 2f)
@Composable
private fun LibraryPreview(@PreviewParameter(LibraryPreviewCases::class) state: LibraryHubUiState) {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.OFF)) {
        LibraryHubScreen(state, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, initialTabName = "ALL")
    }
}

class DownloadPreviewCases : PreviewParameterProvider<List<DownloadEntry>> {
    override val values = sequenceOf(emptyList(), DownloadStatus.entries.mapIndexed { index, status ->
        DownloadEntry(id = status.name, providerId = "demo", providerName = "Демо", releaseId = "title",
            releaseName = "Очень длинное название аниме: путешествие к далёким звёздам",
            episodeId = "ep$index", episodeOrdinal = index.toDouble() + 1, episodeName = null,
            quality = 1080, translation = "Демонстрационная озвучка", translationKey = null,
            sourceName = null, streamType = OnlineStreamType.HLS, status = status,
            progressPercent = if (status == DownloadStatus.DOWNLOADING) 43f else 0f,
            bytesDownloaded = 100_000_000L, contentLength = 230_000_000L,
            speedBytesPerSecond = if (status == DownloadStatus.DOWNLOADING) 1_000_000L else 0L,
            etaSeconds = if (status == DownloadStatus.DOWNLOADING) 130L else null,
            errorMessage = if (status == DownloadStatus.FAILED) "Источник временно недоступен" else null)
    })
}

@Preview(name = "Downloads 360", widthDp = 360, heightDp = 1800)
@Preview(name = "Downloads 840", widthDp = 840, heightDp = 1200)
@Preview(name = "Downloads font 2", widthDp = 320, heightDp = 1800, fontScale = 2f)
@Composable
private fun DownloadsPreview(@PreviewParameter(DownloadPreviewCases::class) entries: List<DownloadEntry>) {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.REDUCED)) {
        DownloadsScreen(entries, {}, {}, {}, {})
    }
}

class CatalogPreviewCases : PreviewParameterProvider<OnlineCatalogUiState> {
    override val values: Sequence<OnlineCatalogUiState> get() {
        val cards = (1..24).map { index -> OnlineReleaseCard("demo", "Демо", "$index", "$index",
            "История за облаками $index", null, null, 2026, "TV", null, 12, index % 2 == 0) }
        val normal = OnlineCatalogUiState(selectedProviderId = "demo", selectedProviderName = "Демо",
            releases = cards, isLoading = false)
        return sequenceOf(normal, normal.copy(query = "облаками", selectedGenre = "Приключения"),
            normal.copy(releases = emptyList(), errorMessage = "Нет ответа от источника"),
            normal.copy(errorMessage = "Один из источников не ответил"), OnlineCatalogUiState())
    }
}

@Preview(name = "Catalog 360", widthDp = 360, heightDp = 1000)
@Preview(name = "Catalog landscape", widthDp = 840, heightDp = 360)
@Preview(name = "Catalog font 2", widthDp = 320, heightDp = 1200, fontScale = 2f)
@Composable
private fun CatalogPreview(@PreviewParameter(CatalogPreviewCases::class) state: OnlineCatalogUiState) {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.OFF)) {
        OnlineCatalogScreen(state, onQueryChange = {}, onRefresh = {}, onRefreshProviderHealth = {},
            onLoadMore = {}, onSelectProvider = {}, onSelectGenre = {}, onSetAdvancedFilter = {},
            onSavePreset = {}, onApplyPreset = {}, onDeletePreset = {}, onSelectCollection = {},
            onSelectSort = {}, onSelectYear = {}, onSelectType = {}, onSelectRating = {},
            onSelectStatus = {}, onSelectEpisodeFilter = {}, onToggleLayout = {}, onResetDiscovery = {},
            onRemoveSearchHistory = {}, onClearSearchHistory = {}, onOpenSettings = {}, onOpenLibrary = {},
            onOpenCollections = {}, onOpenRandomTitle = {}, onSearchTenraiTitle = {}, onOpenTitle = {},
            onPlayEpisode = { _, _, _ -> })
    }
}

@Preview(name = "Catalog filters", widthDp = 320, heightDp = 400, fontScale = 2f)
@Composable
private fun FiltersPreview() {
    AnimeVaultTheme {
        Surface {
            CatalogAdvancedControls(CatalogAdvancedFilter(), listOf("Приключения", "Фантастика"),
                emptyList(), "demo", {}, {}, {}, {})
        }
    }
}
