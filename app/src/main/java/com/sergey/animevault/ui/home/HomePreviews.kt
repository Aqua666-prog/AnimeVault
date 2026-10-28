package com.sergey.animevault.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.ui.components.VaultPosterCard
import com.sergey.animevault.ui.components.VaultPrimaryButton
import com.sergey.animevault.ui.components.VaultSectionHeader
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.theme.AnimeVaultTheme

// Only synthetic presentation data. No ViewModel, repository, credentials or online source.
private fun demoRelease(id: String, name: String, poster: String? = "preview://poster") = OnlineReleaseCard(
    providerId = "preview", providerName = "Демо", id = id, alias = id, name = name,
    englishName = null, posterUrl = poster, year = 2026, type = "TV", season = null,
    episodeCount = 12, isOngoing = id == "1",
)

private val demoContinue = HomeContinueItem.Online(
    providerId = "preview", releaseId = "1", episodeId = "ep7", title = "За облаками",
    posterUri = null, episodeOrdinal = 7.0, providerName = "Демо",
    positionMs = 600_000L, durationMs = 1_440_000L, lastWatchedAt = 1L,
)

class HomePreviewCases : PreviewParameterProvider<HomeUiState> {
    override val values: Sequence<HomeUiState> get() {
        val releases = listOf(demoRelease("1", "За облаками"), demoRelease("2", "Город на краю ночи"),
            demoRelease("3", "Последнее путешествие к далёким звёздам: история, которая никогда не заканчивается"),
            demoRelease("4", "Тихий океан", null))
        val normal = HomeUiState(discovery = HomeDiscoveryState(releases = releases, loading = false),
            onlineFavoriteKeys = setOf("preview|2"))
        return sequenceOf(normal, normal.copy(continueWatching = listOf(demoContinue)),
            normal.copy(discovery = normal.discovery.copy(featuredIndex = 2)),
            normal.copy(discovery = normal.discovery.copy(featuredIndex = 3)),
            HomeUiState(discovery = HomeDiscoveryState(loading = false)),
            HomeUiState(discovery = HomeDiscoveryState(loading = false, message = "Подборки временно недоступны")),
            HomeUiState(),
            normal.copy(discovery = normal.discovery.copy(message = "Не все подборки удалось обновить")))
    }
}

@Preview(name = "Phone 320", widthDp = 320, heightDp = 1200)
@Preview(name = "Phone 360", widthDp = 360, heightDp = 1200)
@Preview(name = "Tablet 600", widthDp = 600, heightDp = 1200)
@Preview(name = "Expanded 840", widthDp = 840, heightDp = 1100)
@Preview(name = "Landscape", widthDp = 840, heightDp = 360)
@Preview(name = "Large font", widthDp = 360, heightDp = 1400, fontScale = 2f)
@Composable
private fun HomeCinematicPreview(@PreviewParameter(HomePreviewCases::class) state: HomeUiState) {
    PreviewHome(state, AppearanceSettings())
}

@Preview(name = "Reduced motion", widthDp = 360, heightDp = 1200)
@Preview(name = "AMOLED reduced", widthDp = 600, heightDp = 1200)
@Composable
private fun HomeReducedMotionPreview() {
    PreviewHome(HomePreviewCases().values.first().copy(continueWatching = listOf(demoContinue)),
        AppearanceSettings(theme = VaultThemeMode.OLED, motion = VaultMotionMode.REDUCED))
}

@Preview(name = "Motion off", widthDp = 360, heightDp = 1200)
@Composable
private fun HomeMotionOffPreview() {
    PreviewHome(HomePreviewCases().values.first(), AppearanceSettings(motion = VaultMotionMode.OFF))
}

@Composable
private fun PreviewHome(state: HomeUiState, settings: AppearanceSettings) {
    AnimeVaultTheme(settings) {
        Surface {
            HomeScreen(state, onOpenOffline = {}, onOpenOnline = {}, onOpenClips = {},
                onOpenSettings = {}, onOpenStatistics = {}, onOpenLocalTitle = {},
                onPlayLocalEpisode = {}, onOpenOnlineTitle = { _, _ -> },
                onPlayOnlineEpisode = { _, _, _ -> }, onDiscoverTitle = {},
                onRefresh = {}, onNextFeatured = {}, onToggleFavorite = {})
        }
    }
}

@Preview(name = "Media components / missing artwork", widthDp = 320, heightDp = 900)
@Composable
private fun MediaComponentsPreview() {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.REDUCED)) {
        Surface {
            Column(Modifier.padding(VaultSpacing.lg), verticalArrangement = Arrangement.spacedBy(VaultSpacing.lg)) {
                VaultSectionHeader("Продолжить просмотр", "Серия и позиция сохранены")
                ContinueWatchingCard(demoContinue, {})
                VaultPosterCard("Последнее путешествие к далёким звёздам", null, {},
                    Modifier.fillMaxWidth(.5f), metadata = "2026 · TV", badge = "В библиотеке")
                VaultPrimaryButton("Продолжить", {}, icon = Icons.Outlined.PlayArrow)
            }
        }
    }
}
