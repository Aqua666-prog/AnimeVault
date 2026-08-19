package com.sergey.animevault.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic surface roles used across AnimeVault instead of arbitrary card colours. */
enum class VaultSurfaceRole {
    Quiet,
    Card,
    Glass,
    Elevated,
    Accent,
}

@Composable
private fun vaultContainerColor(
    role: VaultSurfaceRole,
    accent: Color,
): Color {
    val colors = MaterialTheme.colorScheme
    return when (role) {
        VaultSurfaceRole.Quiet -> colors.surface.copy(alpha = 0.42f)
        VaultSurfaceRole.Card -> colors.surface.copy(alpha = 0.76f)
        VaultSurfaceRole.Glass -> colors.surface.copy(alpha = VaultAlpha.glass)
        VaultSurfaceRole.Elevated -> colors.surfaceVariant.copy(alpha = VaultAlpha.elevated)
        VaultSurfaceRole.Accent -> accent.copy(alpha = 0.105f)
    }
}

@Composable
private fun vaultBorder(
    role: VaultSurfaceRole,
    accent: Color,
): BorderStroke? {
    val colors = MaterialTheme.colorScheme
    return when (role) {
        // Quiet/Card/Elevated surfaces are separated by tone and depth, not boxes around boxes.
        VaultSurfaceRole.Quiet,
        VaultSurfaceRole.Card,
        VaultSurfaceRole.Elevated -> null

        // Glass keeps a restrained edge so overlays remain legible over artwork.
        VaultSurfaceRole.Glass -> BorderStroke(
            VaultSize.hairline,
            colors.outlineVariant.copy(alpha = 0.30f),
        )

        // Accent is intentionally outlined because the border carries state/selection meaning.
        VaultSurfaceRole.Accent -> BorderStroke(
            VaultSize.hairline,
            accent.copy(alpha = 0.28f),
        )
    }
}

private fun vaultShadow(role: VaultSurfaceRole): Dp = when (role) {
    VaultSurfaceRole.Quiet -> 0.dp
    VaultSurfaceRole.Card -> 1.dp
    VaultSurfaceRole.Glass -> 3.dp
    VaultSurfaceRole.Elevated -> 8.dp
    VaultSurfaceRole.Accent -> 1.dp
}

@Composable
fun VaultPanel(
    modifier: Modifier = Modifier,
    role: VaultSurfaceRole = VaultSurfaceRole.Card,
    shape: Shape = MaterialTheme.shapes.large,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = vaultContainerColor(role, accent),
        border = vaultBorder(role, accent),
        tonalElevation = 0.dp,
        shadowElevation = vaultShadow(role),
    ) {
        Box(content = content)
    }
}

/**
 * Clickable counterpart to [VaultPanel]. Keeping interaction and surface roles in one place
 * prevents every screen from inventing its own border, elevation and pressed silhouette.
 */
@Composable
fun VaultInteractivePanel(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: VaultSurfaceRole = VaultSurfaceRole.Card,
    shape: Shape = MaterialTheme.shapes.large,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = vaultContainerColor(role, accent),
        border = vaultBorder(role, accent),
        tonalElevation = 0.dp,
        shadowElevation = vaultShadow(role),
    ) {
        Box(content = content)
    }
}
