package com.sergey.animevault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val VaultColors = darkColorScheme(
    primary = VaultViolet,
    onPrimary = VaultInk,
    primaryContainer = VaultVioletContainer,
    onPrimaryContainer = VaultWhite,
    secondary = VaultAqua,
    onSecondary = Color(0xFF071A18),
    secondaryContainer = VaultAquaContainer,
    onSecondaryContainer = VaultWhite,
    tertiary = VaultRose,
    onTertiary = VaultInk,
    tertiaryContainer = VaultRoseContainer,
    onTertiaryContainer = VaultWhite,
    background = VaultNight,
    onBackground = VaultWhite,
    surface = VaultSurface,
    onSurface = VaultWhite,
    surfaceVariant = VaultSurfaceHigh,
    onSurfaceVariant = VaultMuted,
    surfaceTint = VaultViolet,
    inverseSurface = VaultWhite,
    inverseOnSurface = VaultNight,
    outline = VaultOutline,
    outlineVariant = VaultOutlineSoft,
    scrim = Color.Black,
    error = Color(0xFFFFA4AF),
    onError = VaultInk,
    errorContainer = Color(0xFF582833),
    onErrorContainer = VaultWhite,
)

private val AnimeShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun AnimeVaultTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = VaultColors,
        typography = AnimeVaultTypography,
        shapes = AnimeShapes,
        content = content,
    )
}
