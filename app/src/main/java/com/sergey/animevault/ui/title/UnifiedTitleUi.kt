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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
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
import com.sergey.animevault.ui.components.VaultPrimaryButton
import com.sergey.animevault.ui.components.vaultTitleArtwork
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
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
    scrollState: LazyListState? = null,
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
            backdropSources = vaultTitleArtwork(model.banner, model.metadataPoster, model.providerPoster ?: model.poster),
            posterSources = vaultTitleArtwork(metadataPoster = model.poster, providerPoster = model.providerPoster),
            scrollState = scrollState,
            details = {
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
                    model.scoreLabel,
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.statusLabel?.takeIf(String::isNotBlank)?.let { status ->
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (model.genres.isNotEmpty()) {
                    Text(model.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    VaultPrimaryButton(primaryActionLabel, onPrimaryAction, Modifier.fillMaxWidth(), Icons.Outlined.PlayArrow)
                    Spacer(Modifier.height(9.dp))
                }
                secondaryActions()
            },
        )
        var availabilityExpanded by rememberSaveable(model.title) { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 20.dp)) {
            TextButton(onClick = { availabilityExpanded = !availabilityExpanded }) {
                Text(if (availabilityExpanded) "Скрыть доступность и прогресс" else "Доступность и прогресс")
            }
            if (availabilityExpanded) {
                UnifiedAvailabilityPanel(model, accent, onOpenLocal, onOpenOnline)
                Spacer(Modifier.height(10.dp))
                VaultWatchSummary(model.totalEpisodes, model.completedEpisodes, model.inProgressEpisodes, accent = accent)
            }
        }
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
