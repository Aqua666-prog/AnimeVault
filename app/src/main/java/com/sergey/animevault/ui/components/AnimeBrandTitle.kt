package com.sergey.animevault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Native vector mark: legible at toolbar size, no character illustration behind controls. */
@Composable
fun VaultLogoMark(modifier: Modifier = Modifier) {
    Surface(modifier.size(40.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AnimeBrandTitle(subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(subtitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("AnimeVault", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
