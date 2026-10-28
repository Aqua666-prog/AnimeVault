package com.sergey.animevault.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultPrimaryButton
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.theme.AnimeVaultTheme

internal class PlayerPreviewSnapshots : PreviewParameterProvider<PlayerUiSnapshot> {
    override val values = sequenceOf(
        PlayerUiSnapshot(positionMs = 360_000, durationMs = 1_440_000, bufferedPositionMs = 720_000, isSeekable = true),
        PlayerUiSnapshot(positionMs = 420_000, durationMs = 1_440_000, bufferedPositionMs = 480_000,
            isPlaying = true, isSeekable = true),
        PlayerUiSnapshot(positionMs = 60_000, isBuffering = true),
    )
}

@Preview(name = "Player 320", widthDp = 320, heightDp = 520)
@Preview(name = "Player 360", widthDp = 360, heightDp = 520)
@Preview(name = "Player 600", widthDp = 600, heightDp = 520)
@Preview(name = "Player landscape 840", widthDp = 840, heightDp = 360)
@Preview(name = "Player font 2 / OFF", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun PlayerControlsPreview(@PreviewParameter(PlayerPreviewSnapshots::class) snapshot: PlayerUiSnapshot) {
    AnimeVaultTheme(AppearanceSettings(motion = VaultMotionMode.OFF)) {
        Surface(color = Color.Black) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerNowPlayingBar("История за облаками: очень длинное название аниме", "Серия 7 · HLS · Озвучка")
                PlayerTransportContent(snapshot, {}, {}, {})
                PlayerTimelineContent(snapshot, PlayerSkipSettings(openingEndMs = 90_000,
                    endingStartMs = 1_350_000, endingEndMs = 1_440_000), false, 0, {}, {})
                PlayerTimelineContent(snapshot, PlayerSkipSettings(), true, 600_000, {}, {})
            }
        }
    }
}

class CinematicThemeCases : PreviewParameterProvider<VaultThemeMode> {
    override val values = VaultThemeMode.entries.asSequence()
}

@Preview(name = "Theme roles", widthDp = 360, heightDp = 600, apiLevel = 35)
@Preview(name = "Theme API24 fallback", widthDp = 320, heightDp = 600, apiLevel = 24)
@Composable
private fun ThemeRolesPreview(@PreviewParameter(CinematicThemeCases::class) theme: VaultThemeMode) {
    AnimeVaultTheme(AppearanceSettings(theme = theme, motion = VaultMotionMode.REDUCED)) {
        Surface {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(theme.title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Text("Основной и вторичный текст", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(Modifier.fillMaxWidth(), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer) {
                    Text("Поверхность карточки", Modifier.padding(16.dp))
                }
                VaultPrimaryButton("Продолжить", {})
                PlayerLoadingStatus()
            }
        }
    }
}
