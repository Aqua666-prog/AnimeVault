package com.sergey.animevault.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Premium dark background used behind transparent scaffolds. */
@Composable
fun AnimeBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        VaultNightDeep,
                        Color(0xFF0A0C12),
                        Color(0xFF0C1018),
                        VaultNight,
                    ),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val min = size.minDimension
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(VaultViolet.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.93f, size.height * 0.02f),
                    radius = min * 0.72f,
                ),
                radius = min * 0.72f,
                center = Offset(size.width * 0.93f, size.height * 0.02f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(VaultRose.copy(alpha = 0.075f), Color.Transparent),
                    center = Offset(size.width * 0.06f, size.height * 0.68f),
                    radius = min * 0.66f,
                ),
                radius = min * 0.66f,
                center = Offset(size.width * 0.06f, size.height * 0.68f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(VaultAqua.copy(alpha = 0.045f), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.94f),
                    radius = min * 0.55f,
                ),
                radius = min * 0.55f,
                center = Offset(size.width * 0.92f, size.height * 0.94f),
            )

            // Lumen replaces the old architectural grid with a very soft
            // diagonal light field. Empty screens keep depth without acquiring a
            // gamer-dashboard texture.
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        VaultViolet.copy(alpha = 0.030f),
                        Color.Transparent,
                        VaultAqua.copy(alpha = 0.018f),
                    ),
                    start = Offset(0f, size.height * 0.18f),
                    end = Offset(size.width, size.height * 0.82f),
                ),
            )

            // Deep vignette keeps chrome readable while preserving the
            // coloured ambience under long, mostly transparent screens.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.20f),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}
