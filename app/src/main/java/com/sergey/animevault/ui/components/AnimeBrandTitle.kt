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
    Box(
        modifier = modifier.size(VaultSize.logo).clip(RoundedCornerShape(VaultRadius.small))
            .background(Brush.linearGradient(listOf(Color(0xFF7047EB), Color(0xFF9B62F5), Color(0xFFD9FF4A)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val ink = Color(0xFF0A0810)
            val stroke = 2.6.dp.toPx()
            // V opens the vault, A completes the monogram. Shared with Vault Reveal.
            drawLine(ink, Offset(w * .18f, h * .28f), Offset(w * .39f, h * .73f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink, Offset(w * .39f, h * .73f), Offset(w * .61f, h * .28f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink, Offset(w * .43f, h * .73f), Offset(w * .65f, h * .28f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink, Offset(w * .65f, h * .28f), Offset(w * .84f, h * .73f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink, Offset(w * .53f, h * .56f), Offset(w * .77f, h * .56f), stroke * .85f, androidx.compose.ui.graphics.StrokeCap.Round)
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
