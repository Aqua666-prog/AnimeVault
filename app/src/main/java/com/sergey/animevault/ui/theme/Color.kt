package com.sergey.animevault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Vault Lemon Purple. Actions and navigation have separate semantic colours. */
val VaultViolet = Color(0xFF8B5CF6)
val VaultVioletBright = Color(0xFFA78BFA)
val VaultVioletDark = Color(0xFF6842C2)
val VaultVioletContainer = Color(0xFF302347)
val VaultLemon = Color(0xFFD9FF4A)
val VaultLemonPressed = Color(0xFFC5EB38)
val VaultLemonHighlight = Color(0xFFE6FF87)
val VaultNight = Color(0xFF0A0810)
val VaultNightDeep = Color(0xFF000000)
val VaultNightMiddle = Color(0xFF0C0A10)
val VaultSurface = Color(0xFF121018)
val VaultSurfaceLow = VaultSurface
val VaultSurfaceHigh = Color(0xFF1A1722)
val VaultSurfaceHighest = Color(0xFF24202D)
val VaultSurfaceGlass = Color(0xF224202D)
val VaultWhite = Color(0xFFF7F5FA)
val VaultInk = VaultNight
val VaultMuted = Color(0xFFB4ADBD)
val VaultMutedDim = Color(0xFF756E7E)
val VaultOutline = Color(0xFF342D40)
val VaultOutlineSoft = VaultOutline
val VaultHairline = VaultOutline
val VaultSuccess = Color(0xFF4FD1A1)
val VaultWarning = Color(0xFFF3B74A)
val VaultError = Color(0xFFFF647C)
val VaultInfo = VaultVioletBright

// Compatibility names do not introduce competing brand accents.
val VaultAqua = VaultLemon
val VaultAquaContainer = Color(0xFF30381B)
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
