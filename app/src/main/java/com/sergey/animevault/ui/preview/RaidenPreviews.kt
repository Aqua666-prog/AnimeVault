package com.sergey.animevault.ui.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.dp
import com.sergey.animevault.data.community.CommunityCommentDto
import com.sergey.animevault.ui.community.CommunityCommentCard
import com.sergey.animevault.ui.components.*
import com.sergey.animevault.ui.preferences.*
import com.sergey.animevault.ui.settings.VaultThemePicker
import com.sergey.animevault.ui.startup.RaidenSplashArtwork
import com.sergey.animevault.ui.theme.AnimeVaultTheme

class RaidenPreviewThemes : PreviewParameterProvider<VaultThemeMode> {
    override val values = sequenceOf(VaultThemeMode.EUTHYMIA, VaultThemeMode.MUSOU, VaultThemeMode.VAULT)
}

@Preview(name = "Components / phone", widthDp = 360, heightDp = 1100)
@Preview(name = "Components / large font", widthDp = 320, heightDp = 1600, fontScale = 2f)
@Composable
private fun RaidenComponents(@PreviewParameter(RaidenPreviewThemes::class) mode: VaultThemeMode) {
    AnimeVaultTheme(AppearanceSettings(theme = mode, motion = VaultMotionMode.OFF)) {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AnimeBrandTitle("Plane of Euthymia")
                VaultSearchField("", {}, "Найти свою историю")
                VaultPrimaryButton("Продолжить просмотр", {})
                VaultPrimaryButton("Недоступно", {}, enabled = false)
                VaultPrimaryButton("Загрузка", {}, loading = true)
                VaultFilterChip(true, {}, { Text("Приключения") })
                WatchProgressBar(.6f, Modifier.fillMaxWidth().height(5.dp))
                VaultEmptyState(Icons.Outlined.Search, "Нет результатов", "Измените фильтры или поисковый запрос.")
                CommunityCommentCard(CommunityCommentDto("demo",null,"author","traveler","Путешественник",null,
                    "Люблю эту атмосферу. Жду следующую серию!","visible",false,"2026-01-01","2026-01-01",4,false,2),
                    null,false,true,true,false,{},{},{},{},{},{})
            }
        }
    }
}

@Preview(name = "Theme settings", widthDp = 360, heightDp = 1300)
@Composable
private fun RaidenSettings(@PreviewParameter(RaidenPreviewThemes::class) mode: VaultThemeMode) {
    AnimeVaultTheme(AppearanceSettings(theme = mode, motion = VaultMotionMode.OFF)) {
        Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { VaultThemePicker(mode, {}) } }
    }
}

@Preview(name = "Splash frame", widthDp = 360, heightDp = 800)
@Preview(name = "Splash landscape", widthDp = 800, heightDp = 360)
@Composable
private fun RaidenSplash(@PreviewParameter(RaidenPreviewThemes::class) mode: VaultThemeMode) {
    AnimeVaultTheme(AppearanceSettings(theme = mode, motion = VaultMotionMode.OFF)) { RaidenSplashArtwork(.7f) }
}

class RaidenRevealFrames : PreviewParameterProvider<Pair<VaultThemeMode, Float>> {
    override val values = sequenceOf(VaultThemeMode.EUTHYMIA, VaultThemeMode.MUSOU)
        .flatMap { theme -> sequenceOf(.15f, .45f, .7f).map { theme to it } }
}

@Preview(name = "Reveal phases", widthDp = 360, heightDp = 800)
@Preview(name = "Reveal compact / large text", widthDp = 640, heightDp = 360, fontScale = 1.5f)
@Composable
private fun RaidenRevealPhase(@PreviewParameter(RaidenRevealFrames::class) frame: Pair<VaultThemeMode, Float>) {
    AnimeVaultTheme(AppearanceSettings(theme=frame.first, motion=VaultMotionMode.OFF)) {
        RaidenSplashArtwork(frame.second)
    }
}
