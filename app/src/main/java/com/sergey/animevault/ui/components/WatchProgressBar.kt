package com.sergey.animevault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import com.sergey.animevault.ui.theme.LocalVaultColors
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.theme.vaultMotionDuration

@Composable
fun WatchProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = LocalVaultColors.current.action,
) {
    val raiden = com.sergey.animevault.ui.theme.LocalRaiden.current
    val target = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val duration = vaultMotionDuration(VaultMotion.reveal)
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = duration),
        label = "watch-progress",
    )
    val animationsOff = LocalVaultVisualSettings.current.motion == VaultMotionMode.OFF
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }
            .drawWithCache {
                val gradient = if (raiden.enabled && size.width > 0f) {
                    Brush.horizontalGradient(listOf(raiden.glow, accent, raiden.gold), endX = size.width)
                } else null
                onDrawBehind {
                    drawRect(trackColor)
                    val displayedFraction = if (animationsOff) target else fraction
                    val fill = Size(size.width * displayedFraction, size.height)
                    if (gradient == null) drawRect(accent, size = fill)
                    else drawRect(gradient, size = fill)
                }
            },
    )
}
