package com.sergey.animevault.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Original AnimeVault cold-start ident.
 *
 * The pacing borrows the idea of a short cinematic streaming-service ident,
 * but the artwork is the app's own vault dial: a narrow light seam unlocks,
 * the A mark forms, a ring expands, then the home screen is revealed.
 */
@Composable
fun AnimeVaultLaunchIntro(
    motionScale: Float,
    onFinished: () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.68f) }
    val titleAlpha = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val scale = motionScale.coerceIn(0.18f, 1f)

    fun duration(base: Int): Int = (base * scale).toInt().coerceAtLeast(70)

    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(duration(420), easing = FastOutSlowInEasing),
        )
        logoScale.animateTo(
            targetValue = 1.08f,
            animationSpec = tween(duration(220), easing = FastOutSlowInEasing),
        )
        logoScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(duration(130)),
        )
        titleAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(duration(260)),
        )
        ring.animateTo(
            targetValue = 1f,
            animationSpec = tween(duration(360), easing = FastOutSlowInEasing),
        )
        delay(duration(300).toLong())
        exitAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(duration(300), easing = FastOutSlowInEasing),
        )
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha.value }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f - 12.dp.toPx())
            val seamHeight = size.height * 0.52f * reveal.value
            val seamWidth = 2.dp.toPx() + 5.dp.toPx() * reveal.value
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        primary.copy(alpha = 0.10f),
                        secondary.copy(alpha = 0.92f),
                        primary.copy(alpha = 0.96f),
                        tertiary.copy(alpha = 0.72f),
                        Color.Transparent,
                    ),
                    startY = center.y - seamHeight / 2f,
                    endY = center.y + seamHeight / 2f,
                ),
                topLeft = Offset(center.x - seamWidth / 2f, center.y - seamHeight / 2f),
                size = Size(seamWidth, seamHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(seamWidth, seamWidth),
            )

            if (ring.value > 0f) {
                val radius = size.minDimension * (0.10f + 0.34f * ring.value)
                drawCircle(
                    color = primary.copy(alpha = (1f - ring.value) * 0.55f),
                    radius = radius,
                    center = center,
                    style = Stroke(
                        width = (2.2f - ring.value).coerceAtLeast(0.7f).dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(98.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = reveal.value
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.34f),
                                primary.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                        radius = size.minDimension * 0.56f,
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.96f),
                        radius = size.minDimension * 0.42f,
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.82f),
                        radius = size.minDimension * 0.31f,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.42f),
                        radius = size.minDimension * 0.25f,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
                Text(
                    text = "A",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "ANIMEVAULT",
                color = Color.White.copy(alpha = titleAlpha.value),
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                letterSpacing = (1.5f + 4.5f * titleAlpha.value).sp,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - titleAlpha.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "YOUR ANIME. YOUR VAULT.",
                color = Color.White.copy(alpha = titleAlpha.value * 0.46f),
                fontSize = 9.sp,
                letterSpacing = 2.3.sp,
            )
        }
    }
}
