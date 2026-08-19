package com.sergey.animevault.ui.title

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultAdaptiveHero
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.components.VaultWatchSummary
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultSurfaceRole
import com.sergey.animevault.ui.navigation.VaultSharedPosterKey
import com.sergey.animevault.ui.navigation.vaultSharedPoster
import com.sergey.animevault.ui.theme.vaultAccentFor

@Composable
fun UnifiedTitleOverview(
    model: UnifiedTitleUiModel,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenLocal: ((Long) -> Unit)? = null,
    onOpenOnline: ((String, String) -> Unit)? = null,
    secondaryActions: @Composable () -> Unit = {},
) {
    val accent = vaultAccentFor(model.poster ?: model.title)
    val currentOnlineSource = model.onlineSources.firstOrNull { it.isCurrent }
    val sharedPosterKey = when {
        currentOnlineSource != null -> VaultSharedPosterKey(
            source = "online:${currentOnlineSource.providerId}",
            id = currentOnlineSource.releaseId,
        )
        model.localTitleId != null -> VaultSharedPosterKey("local", model.localTitleId.toString())
        else -> null
    }
    Column(modifier = modifier) {
        VaultAdaptiveHero(
            poster = model.poster,
            seed = model.poster ?: model.title,
            title = model.title,
            posterContentDescription = "Обложка ${model.title}",
            posterModifier = Modifier.vaultSharedPoster(sharedPosterKey),
            details = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (model.origin) {
                        UnifiedTitleOrigin.LOCAL -> VaultStatusPill("ЛОКАЛЬНО", accent = accent)
                        UnifiedTitleOrigin.ONLINE -> VaultStatusPill("ОНЛАЙН", accent = accent)
                        UnifiedTitleOrigin.HYBRID -> VaultStatusPill("ЛОКАЛЬНО + ОНЛАЙН", accent = accent)
                    }
                    if (model.isOngoing) {
                        VaultStatusPill(
                            text = "ВЫХОДИТ",
                            accent = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                model.secondaryTitle?.takeIf(String::isNotBlank)?.let { secondary ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(9.dp))
                val meta = listOfNotNull(
                    model.year?.toString(),
                    model.type,
                    model.season,
                    model.scoreLabel,
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.totalEpisodes > 0) {
                    Text(
                        text = "${model.totalEpisodes} серий",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(primaryActionLabel)
                    }
                    Spacer(Modifier.height(9.dp))
                }
                secondaryActions()
            },
        )
        Spacer(Modifier.height(10.dp))
        UnifiedAvailabilityPanel(
            model = model,
            accent = accent,
            onOpenLocal = onOpenLocal,
            onOpenOnline = onOpenOnline,
        )
        Spacer(Modifier.height(10.dp))
        VaultWatchSummary(
            total = model.totalEpisodes,
            completed = model.completedEpisodes,
            inProgress = model.inProgressEpisodes,
            accent = accent,
        )
    }
}

@Composable
private fun UnifiedAvailabilityPanel(
    model: UnifiedTitleUiModel,
    accent: Color,
    onOpenLocal: ((Long) -> Unit)?,
    onOpenOnline: ((String, String) -> Unit)?,
) {
    VaultPanel(
        role = VaultSurfaceRole.Quiet,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(
                text = "Доступность",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(9.dp))
            if (model.localTitleId != null) {
                AvailabilityRow(
                    icon = Icons.Outlined.Folder,
                    title = "Локально",
                    detail = model.localTitleName
                        ?.takeIf { it.isNotBlank() && !it.equals(model.title, ignoreCase = true) }
                        ?.let { "$it · ${model.localEpisodeCount} серий" }
                        ?: "${model.localEpisodeCount} серий на устройстве",
                    accent = accent,
                    enabled = model.localTitleId != null && onOpenLocal != null,
                    onClick = { model.localTitleId?.let { onOpenLocal?.invoke(it) } },
                )
            } else {
                AvailabilityRow(
                    icon = Icons.Outlined.Folder,
                    title = "Локально",
                    detail = "Нет связанных файлов",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = false,
                    onClick = {},
                )
            }
            Spacer(Modifier.height(8.dp))
            if (model.onlineSources.isEmpty()) {
                AvailabilityRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Онлайн",
                    detail = "Источник не связан",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = false,
                    onClick = {},
                )
            } else {
                Text(
                    text = "Онлайн",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(7.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(
                        items = model.onlineSources,
                        key = { "${it.providerId}:${it.releaseId}" },
                    ) { source ->
                        VaultStatusPill(
                            text = if (source.isCurrent) "${source.name} · сейчас" else source.name,
                            modifier = if (onOpenOnline != null) {
                                Modifier.clickable {
                                    onOpenOnline(source.providerId, source.releaseId)
                                }
                            } else {
                                Modifier
                            },
                            accent = if (source.isCurrent) accent else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            androidx.compose.material3.TextButton(onClick = onClick) {
                Text("Открыть")
            }
        }
    }
}
