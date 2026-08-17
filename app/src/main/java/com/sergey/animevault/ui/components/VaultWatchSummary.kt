package com.sergey.animevault.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Compact viewing dashboard shared by offline and online title pages. */
@Composable
fun VaultWatchSummary(
    total: Int,
    completed: Int,
    inProgress: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return
    val safeCompleted = completed.coerceIn(0, total)
    val safeInProgress = inProgress.coerceIn(0, total - safeCompleted)
    val remaining = (total - safeCompleted).coerceAtLeast(0)
    val fraction = safeCompleted.toFloat() / total.toFloat()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WatchMetric("Просмотрено", "$safeCompleted / $total", Modifier.weight(1f), accent)
                WatchMetric("В процессе", safeInProgress.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                WatchMetric("Осталось", remaining.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(11.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
                        RoundedCornerShape(50),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .background(accent, RoundedCornerShape(50)),
                )
            }
        }
    }
}

@Composable
private fun WatchMetric(
    label: String,
    value: String,
    modifier: Modifier,
    accent: Color,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
