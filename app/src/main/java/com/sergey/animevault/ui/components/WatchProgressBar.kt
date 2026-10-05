package com.sergey.animevault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.sergey.animevault.ui.theme.LocalVaultColors
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.theme.vaultMotionDuration

@Composable
fun WatchProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = LocalVaultColors.current.action,
) {
    val target = progress.coerceIn(0f, 1f)
    val duration = vaultMotionDuration(VaultMotion.reveal)
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = duration),
        label = "watch-progress",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(LocalVaultColors.current.action),
            )
        }
    }
}
