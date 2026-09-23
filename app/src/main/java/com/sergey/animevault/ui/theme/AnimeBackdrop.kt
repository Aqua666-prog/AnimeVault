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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.preferences.VaultThemeMode

/** Product-level background that follows the selected AnimeVault color scheme. */
@Composable
fun AnimeBackdrop(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary
    val tertiary = colors.tertiary
    val isOled = LocalVaultVisualSettings.current.theme == VaultThemeMode.OLED
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isOled) {
                    Brush.verticalGradient(listOf(Color.Black, Color.Black))
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.background,
                            colors.surface.copy(alpha = 0.94f),
                            colors.surfaceVariant.copy(alpha = 0.74f),
                            colors.background,
                        ),
                    )
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val min = size.minDimension
            val topRight = Offset(size.width * 0.97f, size.height * 0.06f)
            val lowerLeft = Offset(size.width * 0.02f, size.height * 0.78f)

            if (!isOled) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.16f), Color.Transparent),
                    center = topRight,
                    radius = min * 0.78f,
                ),
                radius = min * 0.78f,
                center = topRight,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = 0.065f), Color.Transparent),
                    center = lowerLeft,
                    radius = min * 0.68f,
                ),
                radius = min * 0.68f,
                center = lowerLeft,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.04f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.94f),
                    radius = min * 0.52f,
                ),
                radius = min * 0.52f,
                center = Offset(size.width * 0.88f, size.height * 0.94f),
            )

            listOf(0.31f, 0.43f, 0.56f).forEachIndexed { index, fraction ->
                drawCircle(
                    color = primary.copy(alpha = 0.045f - index * 0.008f),
                    radius = min * fraction,
                    center = topRight,
                    style = Stroke(width = (0.8f + index * 0.35f).dp.toPx()),
                )
            }
            drawCircle(
                color = secondary.copy(alpha = 0.025f),
                radius = min * 0.46f,
                center = lowerLeft,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.024f),
                        Color.Transparent,
                        secondary.copy(alpha = 0.014f),
                    ),
                    start = Offset(0f, size.height * 0.18f),
                    end = Offset(size.width, size.height * 0.82f),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.17f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.22f),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
            }
        }
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
    }
}
