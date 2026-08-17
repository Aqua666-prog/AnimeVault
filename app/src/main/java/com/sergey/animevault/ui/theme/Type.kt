package com.sergey.animevault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Uses the platform sans family so the source tree has no bundled font files.
 * The visual identity comes from scale, weight and spacing rather than a
 * fragile custom font asset.
 */
val AnimeVaultFontFamily = FontFamily.SansSerif

private fun vaultText(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = AnimeVaultFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val AnimeVaultTypography = Typography(
    // Lumen: less shouting, more editorial hierarchy. Large text uses
    // semibold weight and tighter tracking; emphasis is left to composition.
    displayLarge = vaultText(48, 54, FontWeight.SemiBold, (-1.05f)),
    displayMedium = vaultText(40, 46, FontWeight.SemiBold, (-0.82f)),
    displaySmall = vaultText(32, 38, FontWeight.SemiBold, (-0.58f)),
    headlineLarge = vaultText(29, 35, FontWeight.SemiBold, (-0.46f)),
    headlineMedium = vaultText(25, 31, FontWeight.SemiBold, (-0.30f)),
    headlineSmall = vaultText(21, 27, FontWeight.SemiBold, (-0.16f)),
    titleLarge = vaultText(20, 26, FontWeight.SemiBold, (-0.24f)),
    titleMedium = vaultText(16, 22, FontWeight.SemiBold, (-0.08f)),
    titleSmall = vaultText(14, 19, FontWeight.SemiBold, 0f),
    bodyLarge = vaultText(16, 25, FontWeight.Normal, (-0.04f)),
    bodyMedium = vaultText(14, 21, FontWeight.Normal, 0f),
    bodySmall = vaultText(12, 18, FontWeight.Normal, 0.04f),
    labelLarge = vaultText(14, 18, FontWeight.Medium, 0.04f),
    labelMedium = vaultText(12, 16, FontWeight.Medium, 0.08f),
    labelSmall = vaultText(11, 15, FontWeight.Medium, 0.12f),
)
