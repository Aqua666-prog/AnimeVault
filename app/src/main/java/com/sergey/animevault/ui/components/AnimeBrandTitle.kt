package com.sergey.animevault.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.design.VaultSize
import com.sergey.animevault.ui.design.VaultSpacing

/** The supplied portrait stays legible at app-bar sizes; never redraw the brand mark. */
@Composable
fun VaultLogoMark(modifier: Modifier = Modifier) {
    AnimeVaultBrandImage(
        portrait = true,
        modifier = modifier.size(VaultSize.logo),
        decodeSize = 128,
    )
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
