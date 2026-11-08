package com.sergey.animevault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.preferences.isRaiden

@Immutable
data class RaidenTokens(
    val enabled: Boolean = false,
    val dynamic: Boolean = false,
    val gold: Color = Color(0xFFD9B976),
    val glow: Color = Color(0xFFB588FF),
    val sakura: Color = Color(0xFFE6B9D9),
    val background: Color = Color(0xFF10091F),
    val dusk: Color = Color(0xFF1D1232),
    val card: Color = Color(0xFF291B43),
    val elevated: Color = Color(0xFF35234F),
    val text: Color = Color(0xFFF5F0FF),
    val muted: Color = Color(0xFFB9ACCF),
)
val LocalRaiden = staticCompositionLocalOf { RaidenTokens() }
internal fun raidenTokens(mode: VaultThemeMode) = RaidenTokens(mode.isRaiden, mode == VaultThemeMode.MUSOU)
internal fun raidenColorScheme(mode: VaultThemeMode) = RaidenTokens(dynamic = mode == VaultThemeMode.MUSOU).let { t ->
    androidx.compose.material3.darkColorScheme(
        primary = if (t.dynamic) t.glow else Color(0xFFDAC5FF), onPrimary = t.background,
        primaryContainer = t.card, onPrimaryContainer = t.text,
        secondary = if (t.dynamic) Color(0xFFDAC5FF) else t.gold, onSecondary = t.background,
        secondaryContainer = t.elevated, onSecondaryContainer = t.text,
        tertiary = t.gold, onTertiary = t.background, tertiaryContainer = t.card, onTertiaryContainer = t.text,
        background = t.background, onBackground = t.text, surface = t.dusk, onSurface = t.text,
        surfaceVariant = t.card, onSurfaceVariant = t.muted,
        surfaceContainerLowest = t.background, surfaceContainerLow = t.dusk, surfaceContainer = t.card,
        surfaceContainerHigh = t.elevated, surfaceContainerHighest = t.elevated,
        surfaceBright = t.elevated, surfaceDim = t.background, surfaceTint = Color.Transparent,
        outline = Color(0xFF806A98), outlineVariant = Color(0xFF49335F),
        error = VaultError, onError = t.background,
    )
}
internal val RaidenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp, 8.dp, 20.dp, 8.dp),
    large = RoundedCornerShape(28.dp, 10.dp, 28.dp, 10.dp),
    extraLarge = RoundedCornerShape(32.dp, 12.dp, 32.dp, 12.dp),
)
internal val RaidenTypography = AnimeVaultTypography.let { t -> t.copy(
    displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif),
    headlineLarge = t.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif),
) }
