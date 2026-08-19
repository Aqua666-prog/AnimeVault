package com.sergey.animevault.ui.design

import androidx.compose.ui.unit.dp

/**
 * AnimeVault 1.4 design tokens.
 *
 * Screens should prefer these values over one-off dimensions so spacing, motion and
 * silhouettes stay consistent while the UI evolves across phone, tablet and TV.
 */
object VaultSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object VaultRadius {
    val micro = 8.dp
    val small = 12.dp
    val medium = 16.dp
    val large = 22.dp
    val extraLarge = 28.dp
    val hero = 32.dp
}

object VaultSize {
    val touchTarget = 48.dp
    val topBarAction = 48.dp
    val compactIcon = 18.dp
    val icon = 20.dp
    val largeIcon = 28.dp
    val logo = 42.dp
    val hairline = 1.dp
    val progress = 5.dp
}

object VaultMotion {
    const val pressIn = 82
    const val pressOut = 148
    const val fast = 150
    const val standard = 220
    const val reveal = 320
    const val slow = 420
    const val skeleton = 900
}

object VaultAlpha {
    const val disabled = 0.38f
    const val muted = 0.68f
    const val subtle = 0.10f
    const val outline = 0.58f
    const val glass = 0.88f
    const val elevated = 0.96f
}
