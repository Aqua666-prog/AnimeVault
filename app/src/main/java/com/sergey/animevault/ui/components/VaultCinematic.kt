package com.sergey.animevault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultSurfaceRole
import com.sergey.animevault.ui.theme.vaultAccentFor
import com.sergey.animevault.ui.theme.vaultBlurEnabled
import com.sergey.animevault.ui.theme.vaultMotionDuration
import kotlinx.coroutines.delay

/**
 * Cinematic poster aura for hero blocks.
 *
 * It intentionally does not own the container. Screens keep their existing
 * Surface semantics while this layer supplies a blurred artwork wash, a
 * poster-derived stable accent and legibility scrims underneath the content.
 */
@Composable
fun BoxScope.VaultPosterAura(
    poster: Any?,
    seed: String,
    modifier: Modifier = Modifier,
) {
    val accent = remember(seed) { vaultAccentFor(seed) }
    val blurEnabled = vaultBlurEnabled()

    if (poster != null) {
        AsyncImage(
            model = poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.14f
                    scaleY = 1.14f
                }
                .then(if (blurEnabled) Modifier.blur(24.dp) else Modifier)
                .alpha(if (blurEnabled) 0.24f else 0.15f),
        )
    }

    Box(
        modifier = modifier
            .matchParentSize()
            .background(
                Brush.linearGradient(
                    0.0f to accent.copy(alpha = 0.28f),
                    0.44f to accent.copy(alpha = 0.07f),
                    0.78f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.10f),
                ),
            ),
    )
    Box(
        modifier = modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.10f),
                    0.45f to Color.Black.copy(alpha = 0.24f),
                    1.0f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
    )
}

/**
 * Responsive title hero used by both offline and online detail screens.
 *
 * Phones keep the compact poster-and-copy composition. Tablets, foldables and
 * landscape windows promote the poster and title into a wider editorial hero
 * instead of simply stretching the phone card.
 */
@Composable
fun VaultAdaptiveHero(
    poster: Any?,
    seed: String,
    title: String,
    posterContentDescription: String,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier,
    details: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val accent = remember(seed) { vaultAccentFor(seed) }
    val revealDuration = vaultMotionDuration(VaultMotion.reveal)
    val revealDelay = vaultMotionDuration(24)
    var revealTarget by remember(seed) { mutableFloatStateOf(0.965f) }
    val reveal by animateFloatAsState(
        targetValue = revealTarget,
        animationSpec = tween(durationMillis = revealDuration),
        label = "vault-hero-reveal",
    )
    LaunchedEffect(seed) {
        revealTarget = 0.965f
        delay(revealDelay.toLong())
        revealTarget = 1f
    }

    VaultPanel(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = reveal
                scaleY = reveal
                alpha = ((reveal - 0.94f) / 0.06f).coerceIn(0.72f, 1f)
            },
        role = VaultSurfaceRole.Glass,
        shape = RoundedCornerShape(VaultRadius.extraLarge),
        accent = accent,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val expanded = maxWidth >= 600.dp
            VaultPosterAura(poster = poster, seed = seed)

            if (expanded) {
                Row(
                    modifier = Modifier.padding(VaultSpacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(VaultSpacing.xxl),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (poster != null) {
                        AsyncImage(
                            model = poster,
                            contentDescription = posterContentDescription,
                            contentScale = ContentScale.Crop,
                            modifier = posterModifier
                                .width(166.dp)
                                .height(238.dp)
                                .clip(RoundedCornerShape(21.dp)),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 620.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(VaultSpacing.md))
                        details()
                        Spacer(Modifier.height(VaultSpacing.xl))
                        actions()
                    }
                }
            } else {
                Column(Modifier.padding(VaultSpacing.lg)) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (poster != null) {
                            AsyncImage(
                                model = poster,
                                contentDescription = posterContentDescription,
                                contentScale = ContentScale.Crop,
                                modifier = posterModifier
                                    .width(106.dp)
                                    .height(154.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            Spacer(Modifier.width(VaultSpacing.lg))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(VaultSpacing.sm))
                            details()
                        }
                    }
                    Spacer(Modifier.height(VaultSpacing.lg))
                    actions()
                }
            }
        }
    }
}
