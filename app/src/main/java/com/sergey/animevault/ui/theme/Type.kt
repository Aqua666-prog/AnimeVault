package com.sergey.animevault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Editorial typography for AnimeVault 1.4.
 *
 * No bundled font assets are required. Hierarchy comes from restrained weight,
 * tight display tracking and generous reading line-height.
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
    displayLarge = vaultText(46, 52, FontWeight.SemiBold, -0.96f),
    displayMedium = vaultText(38, 44, FontWeight.SemiBold, -0.72f),
    displaySmall = vaultText(31, 37, FontWeight.SemiBold, -0.48f),
    headlineLarge = vaultText(28, 34, FontWeight.SemiBold, -0.34f),
    headlineMedium = vaultText(24, 30, FontWeight.SemiBold, -0.22f),
    headlineSmall = vaultText(21, 27, FontWeight.SemiBold, -0.12f),
    titleLarge = vaultText(20, 26, FontWeight.SemiBold, -0.14f),
    titleMedium = vaultText(16, 22, FontWeight.SemiBold, -0.04f),
    titleSmall = vaultText(14, 19, FontWeight.SemiBold, 0f),
    bodyLarge = vaultText(16, 25, FontWeight.Normal, -0.02f),
    bodyMedium = vaultText(14, 21, FontWeight.Normal, 0f),
    bodySmall = vaultText(12, 18, FontWeight.Normal, 0.04f),
    labelLarge = vaultText(14, 18, FontWeight.Medium, 0.04f),
    labelMedium = vaultText(12, 16, FontWeight.Medium, 0.08f),
    labelSmall = vaultText(11, 15, FontWeight.Medium, 0.12f),
)
