package com.sergey.animevault.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSize
import com.sergey.animevault.ui.design.VaultSpacing

/** Compact brand mark shared by app bars, loading states and future TV chrome. */
@Composable
fun VaultLogoMark(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(VaultSize.logo)
            .clip(RoundedCornerShape(VaultRadius.medium))
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.96f),
                        colors.tertiary.copy(alpha = 0.84f),
                        colors.secondary.copy(alpha = 0.74f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Color.White.copy(alpha = 0.34f),
                radius = size.minDimension * 0.39f,
                center = center,
                style = Stroke(width = 1.1.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = size.minDimension * 0.29f,
                center = center,
                style = Stroke(width = 0.8.dp.toPx()),
            )
            // Four restrained "locking pins" make the mark read as a vault dial.
            val pinRadius = size.minDimension * 0.025f
            val pinDistance = size.minDimension * 0.36f
            listOf(
                Offset(center.x, center.y - pinDistance),
                Offset(center.x + pinDistance, center.y),
                Offset(center.x, center.y + pinDistance),
                Offset(center.x - pinDistance, center.y),
            ).forEach { drawCircle(Color.White.copy(alpha = 0.54f), pinRadius, it) }
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(VaultRadius.small))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun AnimeBrandTitle(subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VaultLogoMark()
        Spacer(Modifier.width(VaultSpacing.md))
        Column {
            Text(
                text = "AnimeVault",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.84f)),
                )
                Spacer(Modifier.width(VaultSpacing.xs))
                Text(
                    text = subtitle.uppercase(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
