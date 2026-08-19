package com.sergey.animevault.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultAccentMode
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import kotlin.math.roundToInt

data class VaultVisualSettings(
    val theme: VaultThemeMode = VaultThemeMode.VAULT,
    val blurEnabled: Boolean = true,
    val motion: VaultMotionMode = VaultMotionMode.FULL,
)

val LocalVaultVisualSettings = staticCompositionLocalOf { VaultVisualSettings() }

@Composable
fun vaultMotionDuration(baseMillis: Int): Int =
    (baseMillis * LocalVaultVisualSettings.current.motion.durationScale)
        .roundToInt()
        .coerceAtLeast(1)

@Composable
fun vaultBlurEnabled(): Boolean = LocalVaultVisualSettings.current.blurEnabled

private data class AccentColors(
    val primary: Color,
    val container: Color,
    val onPrimary: Color = VaultInk,
)

private fun selectedAccent(mode: VaultAccentMode, fallback: AccentColors): AccentColors = when (mode) {
    VaultAccentMode.VIOLET -> AccentColors(Color(0xFFA78BFA), Color(0xFF302654))
    VaultAccentMode.BLUE -> AccentColors(Color(0xFF60A5FA), Color(0xFF183B63), onPrimary = Color(0xFF061523))
    VaultAccentMode.RED -> AccentColors(Color(0xFFFB7185), Color(0xFF572331), onPrimary = Color(0xFF24070C))
    VaultAccentMode.SYSTEM -> fallback
}

private fun vaultScheme(
    accentMode: VaultAccentMode,
    systemAccent: AccentColors? = null,
): ColorScheme {
    val defaultAccent = AccentColors(VaultViolet, VaultVioletContainer)
    val accent = selectedAccent(accentMode, systemAccent ?: defaultAccent)
    return darkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.container,
        onPrimaryContainer = VaultWhite,
        secondary = VaultAqua,
        onSecondary = Color(0xFF071A18),
        secondaryContainer = VaultAquaContainer,
        onSecondaryContainer = VaultWhite,
        tertiary = VaultRose,
        onTertiary = VaultInk,
        tertiaryContainer = VaultRoseContainer,
        onTertiaryContainer = VaultWhite,
        background = Color(0xFF0B0D12),
        onBackground = VaultWhite,
        surface = Color(0xFF11151D),
        onSurface = VaultWhite,
        surfaceVariant = Color(0xFF171C26),
        onSurfaceVariant = VaultMuted,
        surfaceTint = accent.primary,
        inverseSurface = VaultWhite,
        inverseOnSurface = VaultNight,
        outline = VaultOutline,
        outlineVariant = VaultOutlineSoft,
        scrim = Color.Black,
        error = VaultError,
        onError = VaultInk,
        errorContainer = Color(0xFF522631),
        onErrorContainer = VaultWhite,
    )
}

private fun midnightScheme(
    accentMode: VaultAccentMode,
    systemAccent: AccentColors? = null,
): ColorScheme {
    val defaultAccent = AccentColors(Color(0xFF7DB8FF), Color(0xFF193A61), onPrimary = Color(0xFF051523))
    val accent = selectedAccent(accentMode, systemAccent ?: defaultAccent)
    return darkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.container,
        onPrimaryContainer = Color(0xFFF3F7FF),
        secondary = Color(0xFF75C9D7),
        onSecondary = Color(0xFF06171B),
        secondaryContainer = Color(0xFF15343D),
        onSecondaryContainer = Color(0xFFF3FAFF),
        tertiary = Color(0xFFB6A2E9),
        onTertiary = Color(0xFF120D1D),
        tertiaryContainer = Color(0xFF30274A),
        onTertiaryContainer = Color(0xFFF8F3FF),
        background = Color(0xFF070B12),
        onBackground = Color(0xFFF1F5FB),
        surface = Color(0xFF0C121C),
        onSurface = Color(0xFFF1F5FB),
        surfaceVariant = Color(0xFF131C29),
        onSurfaceVariant = Color(0xFFB4BFCD),
        surfaceTint = accent.primary,
        outline = Color(0xFF3A4657),
        outlineVariant = Color(0xFF222C3A),
        scrim = Color.Black,
        error = Color(0xFFFF8798),
        onError = Color(0xFF24070C),
    )
}

private fun oledScheme(
    accentMode: VaultAccentMode,
    systemAccent: AccentColors? = null,
): ColorScheme {
    val defaultAccent = AccentColors(Color(0xFFB39AFB), Color(0xFF261F42))
    val accent = selectedAccent(accentMode, systemAccent ?: defaultAccent)
    return darkColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.container,
        onPrimaryContainer = VaultWhite,
        secondary = Color(0xFF66D6CE),
        onSecondary = Color.Black,
        tertiary = Color(0xFFF08FA4),
        onTertiary = Color.Black,
        background = Color.Black,
        onBackground = Color(0xFFF7F7F8),
        surface = Color.Black,
        onSurface = Color(0xFFF7F7F8),
        surfaceVariant = Color(0xFF0B0C10),
        onSurfaceVariant = Color(0xFFB8BBC4),
        surfaceTint = accent.primary,
        outline = Color(0xFF35373F),
        outlineVariant = Color(0xFF1A1B20),
        scrim = Color.Black,
        error = Color(0xFFFF8296),
        onError = Color.Black,
    )
}

@Composable
private fun dynamicScheme(settings: AppearanceSettings): ColorScheme {
    val context = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        vaultScheme(VaultAccentMode.SYSTEM)
    }
    if (settings.accent == VaultAccentMode.SYSTEM) return base
    val accent = selectedAccent(settings.accent, AccentColors(base.primary, base.primaryContainer, base.onPrimary))
    return base.copy(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.container,
        onPrimaryContainer = VaultWhite,
        surfaceTint = accent.primary,
    )
}

private val AnimeShapes = Shapes(
    extraSmall = RoundedCornerShape(VaultRadius.micro),
    small = RoundedCornerShape(VaultRadius.small),
    medium = RoundedCornerShape(VaultRadius.medium),
    large = RoundedCornerShape(VaultRadius.large),
    extraLarge = RoundedCornerShape(VaultRadius.extraLarge),
)

@Composable
fun AnimeVaultTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemAccent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).let { dynamic ->
            AccentColors(
                primary = dynamic.primary,
                container = dynamic.primaryContainer,
                onPrimary = dynamic.onPrimary,
            )
        }
    } else {
        null
    }
    val colors = when (settings.theme) {
        VaultThemeMode.VAULT -> vaultScheme(settings.accent, systemAccent)
        VaultThemeMode.MIDNIGHT -> midnightScheme(settings.accent, systemAccent)
        VaultThemeMode.OLED -> oledScheme(settings.accent, systemAccent)
        VaultThemeMode.DYNAMIC -> dynamicScheme(settings)
    }
    CompositionLocalProvider(
        LocalVaultVisualSettings provides VaultVisualSettings(
            theme = settings.theme,
            blurEnabled = settings.blurEnabled,
            motion = settings.motion,
        ),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AnimeVaultTypography,
            shapes = AnimeShapes,
            content = content,
        )
    }
}
