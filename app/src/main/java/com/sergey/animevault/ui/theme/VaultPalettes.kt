package com.sergey.animevault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.sergey.animevault.ui.preferences.VaultThemeMode

/** Static palettes also provide the Android 7–11 Dynamic fallback. No preference keys change. */
internal fun vaultStaticColorScheme(mode: VaultThemeMode): ColorScheme {
    val amoled = mode == VaultThemeMode.OLED
    val background = if (amoled) Color.Black else VaultNight
    val card = if (amoled) VaultNightMiddle else VaultSurfaceHigh
    val base =
        darkColorScheme(
            primary = VaultVioletBright,
            onPrimary = VaultInk,
            primaryContainer = VaultVioletContainer,
            onPrimaryContainer = VaultWhite,
            secondary = VaultLemon,
            onSecondary = VaultInk,
            secondaryContainer = VaultAquaContainer,
            onSecondaryContainer = VaultLemonHighlight,
            tertiary = VaultViolet,
            onTertiary = VaultWhite,
            tertiaryContainer = VaultVioletContainer,
            onTertiaryContainer = VaultWhite,
            background = background,
            onBackground = VaultWhite,
            surface = if (amoled) VaultNightMiddle else VaultSurface,
            onSurface = VaultWhite,
            surfaceVariant = card,
            onSurfaceVariant = VaultMuted,
            surfaceContainerLowest = background,
            surfaceContainerLow = if (amoled) VaultNightMiddle else VaultSurface,
            surfaceContainer = card,
            surfaceContainerHigh = VaultSurfaceHighest,
            surfaceContainerHighest = VaultSurfaceHighest,
            surfaceTint = Color.Transparent,
            outline = VaultOutline,
            outlineVariant = VaultOutlineSoft,
            scrim = Color.Black,
            error = VaultError,
            onError = VaultInk,
            errorContainer = Color(0xFF44202A),
            onErrorContainer = VaultWhite,
            inverseSurface = VaultWhite,
            inverseOnSurface = VaultNight,
        )
    return if (mode == VaultThemeMode.MIDNIGHT) base.copy(
        primary = Color(0xFFAFC7FF), onPrimary = VaultInk,
        primaryContainer = Color(0xFF223857), onPrimaryContainer = VaultWhite,
        tertiary = Color(0xFFADC6E7), onTertiary = VaultInk,
        tertiaryContainer = Color(0xFF1F344E), onTertiaryContainer = VaultWhite,
        background = Color(0xFF070E19), onBackground = VaultWhite,
        surface = Color(0xFF0D1624), onSurface = VaultWhite,
        surfaceVariant = Color(0xFF152236), onSurfaceVariant = Color(0xFFBBC7DC),
        surfaceContainerLowest = Color(0xFF070E19), surfaceContainerLow = Color(0xFF0D1624),
        surfaceContainer = Color(0xFF152236), surfaceContainerHigh = Color(0xFF1D2E45),
        surfaceContainerHighest = Color(0xFF263950),
        outline = Color(0xFF63758E), outlineVariant = Color(0xFF35475F),
    ) else base
}
