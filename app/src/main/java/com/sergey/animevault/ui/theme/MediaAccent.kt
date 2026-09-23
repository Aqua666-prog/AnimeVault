package com.sergey.animevault.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Stable media accent chosen from a curated premium palette.
 *
 * The seed is normally the poster URL (falling back to the title), so the same
 * artwork keeps the same chromatic identity across catalog, details and player
 * chrome without introducing another image-analysis dependency.
 */
fun vaultAccentFor(seed: String): Color {
    val swatches = arrayOf(
        VaultViolet,
        VaultAqua,
        VaultRose,
        VaultGold,
        Color(0xFF89B4FA), // ice blue
        Color(0xFFCBA6F7), // orchid
        Color(0xFF94E2D5), // mint
        Color(0xFFF2CDCD), // pearl rose
    )
    val hash = seed.fold(17) { acc, char -> acc * 31 + char.code }
    return swatches[abs(hash % swatches.size)]
}
