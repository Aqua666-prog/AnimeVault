package com.sergey.animevault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.navigation.VaultSharedPosterKey
import com.sergey.animevault.ui.navigation.vaultSharedPoster

/** Poster-led media card used by Home, discovery and the library. */
@Composable
fun VaultPosterCard(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    badge: String? = null,
    sharedKey: VaultSharedPosterKey? = null,
) {
    Column(modifier.vaultClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            VaultArtwork(
                sources = vaultTitleArtwork(providerPoster = poster),
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .vaultSharedPoster(sharedKey).clip(RoundedCornerShape(12.dp)),
                contentDescription = null,
            )
            badge?.let {
                VaultStatusPill(it, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        metadata?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
