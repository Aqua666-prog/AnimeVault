package com.sergey.animevault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Ink & Tide: neutral reading surfaces, sea-glass actions, warm paper text.
 * Compatibility names are retained so saved themes and existing callers remain stable. */
val VaultViolet = Color(0xFF71BEB5)
val VaultVioletBright = Color(0xFF98D8CE)
val VaultVioletDark = Color(0xFF438C84)
val VaultVioletContainer = Color(0xFF203D3A)
val VaultLemon = Color(0xFF98D8CE)
val VaultLemonPressed = Color(0xFF7AC2B7)
val VaultLemonHighlight = Color(0xFFC3EEE5)
val VaultNight = Color(0xFF101416)
val VaultNightDeep = Color(0xFF000000)
val VaultNightMiddle = Color(0xFF13191B)
val VaultSurface = Color(0xFF171D20)
val VaultSurfaceLow = VaultSurface
val VaultSurfaceHigh = Color(0xFF1D2528)
val VaultSurfaceHighest = Color(0xFF293337)
val VaultSurfaceGlass = Color(0xF2293337)
val VaultWhite = Color(0xFFF2F1EA)
val VaultInk = VaultNight
val VaultMuted = Color(0xFFB7C4C5)
val VaultMutedDim = Color(0xFF9EADAF)
val VaultOutline = Color(0xFF526366)
val VaultOutlineSoft = VaultOutline
val VaultHairline = VaultOutline
val VaultSuccess = Color(0xFF98D8CE)
val VaultWarning = Color(0xFFE8C387)
val VaultError = Color(0xFFFFB4AB)
val VaultInfo = VaultVioletBright

// Compatibility names do not introduce competing brand accents.
val VaultAqua = VaultLemon
val VaultAquaContainer = Color(0xFF203D3A)
val VaultRose = VaultVioletBright
val VaultRoseContainer = VaultVioletContainer
val VaultGold = VaultWarning
val VaultLavender = VaultViolet
val VaultLavenderContainer = VaultVioletContainer

@Immutable
data class VaultColors(
    val action: Color = VaultLemon,
    val actionPressed: Color = VaultLemonPressed,
    val onAction: Color = VaultInk,
    val navigation: Color = VaultViolet,
    val highlight: Color = VaultLemonHighlight,
    val card: Color = VaultSurfaceHigh,
    val elevated: Color = VaultSurfaceHighest,
    val inactive: Color = VaultMutedDim,
    val success: Color = VaultSuccess,
    val warning: Color = VaultWarning,
)

val LocalVaultColors = staticCompositionLocalOf { VaultColors() }
