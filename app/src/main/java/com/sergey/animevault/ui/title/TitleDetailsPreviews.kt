package com.sergey.animevault.ui.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.ui.online.OnlineEpisodeCard
import com.sergey.animevault.ui.online.OnlineTitleExtrasSection
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.AnimeVaultTheme

class TitlePreviewModels : PreviewParameterProvider<UnifiedTitleUiModel> {
    override val values = sequenceOf(
        UnifiedTitleUiModel("Город за облаками", secondaryTitle = "Beyond the clouds", year = 2026,
            type = "TV", totalEpisodes = 12, completedEpisodes = 4, inProgressEpisodes = 1,
            scoreLabel = "★ 8.4", genres = listOf("Приключения", "Фантастика"), statusLabel = "Сейчас выходит",
            onlineSources = listOf(UnifiedTitleSourceUi("demo", "release-1", "Демо", true))),
        UnifiedTitleUiModel("Локальная коллекция", totalEpisodes = 24, completedEpisodes = 12,
            localTitleId = 1L, localEpisodeCount = 24),
        UnifiedTitleUiModel("Невероятно длинное название аниме о последнем путешествии к далёким звёздам и возвращении домой",
            secondaryTitle = "A very long alternative title", totalEpisodes = 12),
        UnifiedTitleUiModel("Нет постера и части метаданных"),
    )
}

@Preview(name = "320", widthDp = 320, heightDp = 1200)
@Preview(name = "360", widthDp = 360, heightDp = 1200)
@Preview(name = "600", widthDp = 600, heightDp = 1200)
@Preview(name = "840", widthDp = 840, heightDp = 1200)
@Preview(name = "Landscape", widthDp = 840, heightDp = 360)
@Preview(name = "Font 2 / Reduced", widthDp = 360, heightDp = 1400, fontScale = 2f)
@Composable
private fun TitleOverviewPreview(@PreviewParameter(TitlePreviewModels::class) model: UnifiedTitleUiModel) {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.REDUCED)) {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UnifiedTitleOverview(model, if (model.inProgressEpisodes > 0) "Продолжить" else "Смотреть", {},
                    onOpenLocal = {}, onOpenOnline = { _, _ -> })
            }
        }
    }
}

@Preview(name = "Episodes / Offline / Online / Resume", widthDp = 360, heightDp = 1100)
@Preview(name = "Episodes font 2 / OFF", widthDp = 320, heightDp = 1400, fontScale = 2f)
@Composable
private fun TitleEpisodesPreview() {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.OFF)) {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Сезон 1 · Серии", Modifier.padding(20.dp))
                val episode = EpisodeRow(1L, 1L, "", "Очень длинное имя локального видеофайла.mkv",
                    1.0, 1, 1_440_000L, 1024L, "video/mp4", "01", 600_000L, false, null)
                EpisodeCard(episode, false, false, {}, {}, {})
                EpisodeCard(episode.copy(id = 2L, episodeNumber = 2.0, isCompleted = true), false, false, {}, {}, {})
                OnlineEpisodeCard(OnlineEpisode("demo", "ep-3", "release-1", 3.0,
                    "Длинное название серии и дорога к далёкому морю", null, 1_440_000L, 3.0,
                    emptyList(), sourceRef = "preview-only"), null,
                    OnlineWatchProgress(positionMs = 600_000L, durationMs = 1_440_000L), null,
                    {}, {}, {}, {}, {}, {})
            }
        }
    }
}

@Preview(name = "Metadata error / Retry", widthDp = 320, heightDp = 600)
@Composable
private fun TitleErrorPreview() {
    AnimeVaultTheme {
        Surface(Modifier.fillMaxWidth()) {
            OnlineTitleExtrasSection(null, false, "Сведения временно недоступны", {}, { it })
        }
    }
}
