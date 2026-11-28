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
import com.sergey.animevault.ui.design.raidenEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultRadius
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
    imageFirst: Boolean = false,
) {
    val raiden = com.sergey.animevault.ui.theme.LocalRaiden.current.enabled
    val artwork = remember(poster) { vaultTitleArtwork(providerPoster = poster) }
    val cardShape = MaterialTheme.shapes.large
    val styled = modifier.clip(if (imageFirst) RoundedCornerShape(VaultRadius.small) else cardShape)
        .then(if (imageFirst) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainer))
        .then(if (raiden && !imageFirst) Modifier.raidenEdge() else Modifier)
        .vaultClickable(onClick = onClick)
        .then(if (imageFirst) Modifier else Modifier.padding(VaultSpacing.sm))
    Column(styled, verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            VaultArtwork(
                sources = artwork,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .vaultSharedPoster(sharedKey).clip(RoundedCornerShape(VaultRadius.small)),
                contentDescription = null,
            )
            badge?.let {
                VaultStatusPill(it, Modifier.align(Alignment.TopStart).padding(VaultSpacing.sm)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)))
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        metadata?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
