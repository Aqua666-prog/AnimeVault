package com.sergey.animevault.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Cinematic legibility layer shared by local and online players. */
@Composable
internal fun PlayerChromeScrims(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.66f),
                    0.16f to Color.Black.copy(alpha = 0.12f),
                    0.50f to Color.Transparent,
                    0.78f to Color.Black.copy(alpha = 0.08f),
                    1.0f to Color.Black.copy(alpha = 0.76f),
                ),
            ),
    )
}

/** Small glassy player action. The active state keeps the chrome quiet but legible. */
@Composable
internal fun PlayerChromeButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.38f),
        contentColor = tint,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
            else Color.White.copy(alpha = 0.12f),
        ),
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = tint)
        }
    }
}

/**
 * Responsive action dock. Portrait keeps the familiar right-side rail while
 * landscape turns the same controls into a compact horizontal cluster.
 */
@Composable
internal fun PlayerChromeDock(
    landscape: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (landscape) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            content()
        }
    }
}

/** Compact two-level caption that replaces the old one-line telemetry slab. */
@Composable
internal fun PlayerNowPlayingBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {}
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.92f),
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
        }
    }
}


/** Context card shown only while playback is paused. */
@Composable
internal fun PlayerPauseInfoOverlay(
    title: String,
    episodeLabel: String?,
    remainingMs: Long,
    nextLabel: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 330.dp),
        color = Color.Black.copy(alpha = 0.56f),
        contentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episodeLabel?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
            if (remainingMs > 0L) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Осталось ${formatPauseRemaining(remainingMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            nextLabel?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.size(5.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatPauseRemaining(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
