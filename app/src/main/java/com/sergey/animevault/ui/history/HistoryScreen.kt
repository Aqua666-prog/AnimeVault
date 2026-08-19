package com.sergey.animevault.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultFilterChip
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.design.VaultInteractivePanel
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultSurfaceRole
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onOpenSettings: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        uiState = uiState,
        onFilterChange = viewModel::setFilter,
        onOpenSettings = onOpenSettings,
        onOpenLocalTitle = onOpenLocalTitle,
        onPlayLocalEpisode = onPlayLocalEpisode,
        onOpenOnlineTitle = onOpenOnlineTitle,
        onPlayOnlineEpisode = onPlayOnlineEpisode,
    )
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onFilterChange: (HistoryFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { AnimeBrandTitle("История") },
                actions = {
                    VaultTopBarAction(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        onClick = onOpenSettings,
                    )
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
        ) {
            item(key = "history-filters") {
                HistoryFilterRow(
                    selected = uiState.filter,
                    localCount = uiState.localCount,
                    onlineCount = uiState.onlineCount,
                    onSelected = onFilterChange,
                )
            }

            if (uiState.items.isEmpty()) {
                item(key = "history-empty") {
                    VaultEmptyState(
                        icon = Icons.Outlined.History,
                        title = "История пока пуста",
                        body = "Запущенные локальные серии и открытые онлайн-тайтлы появятся здесь в общей хронологии.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(
                    items = uiState.items,
                    key = { item ->
                        when (item) {
                            is HistoryItem.Local -> "local-${item.row.episodeId}"
                            is HistoryItem.Online -> "online-${item.entry.providerId}-${item.entry.releaseId}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is HistoryItem.Local -> LocalHistoryCard(
                            item = item,
                            onOpen = { onOpenLocalTitle(item.row.titleId) },
                            onPlay = { onPlayLocalEpisode(item.row.episodeId) },
                        )

                        is HistoryItem.Online -> OnlineHistoryCard(
                            item = item,
                            onOpen = { onOpenOnlineTitle(item.entry.providerId, item.entry.releaseId) },
                            onPlay = item.entry.lastEpisodeId?.let { episodeId ->
                                {
                                    onPlayOnlineEpisode(
                                        item.entry.providerId,
                                        item.entry.releaseId,
                                        episodeId,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterRow(
    selected: HistoryFilter,
    localCount: Int,
    onlineCount: Int,
    onSelected: (HistoryFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = VaultSpacing.lg, vertical = VaultSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
    ) {
        VaultFilterChip(
            selected = selected == HistoryFilter.ALL,
            onClick = { onSelected(HistoryFilter.ALL) },
            label = { Text("Все ${localCount + onlineCount}") },
        )
        VaultFilterChip(
            selected = selected == HistoryFilter.LOCAL,
            onClick = { onSelected(HistoryFilter.LOCAL) },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("Локально $localCount") },
        )
        VaultFilterChip(
            selected = selected == HistoryFilter.ONLINE,
            onClick = { onSelected(HistoryFilter.ONLINE) },
            leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("Онлайн $onlineCount") },
        )
    }
}

@Composable
private fun LocalHistoryCard(
    item: HistoryItem.Local,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val row = item.row
    HistoryCard(
        title = row.titleName,
        poster = row.posterUri,
        sourceLabel = "Локально",
        sourceAccent = MaterialTheme.colorScheme.secondary,
        episodeLabel = episodeLabel(row.seasonNumber, row.episodeNumber),
        timestamp = row.lastWatchedAt,
        progress = row.progressFraction,
        completed = row.isCompleted,
        onOpen = onOpen,
        onPlay = onPlay,
    )
}

@Composable
private fun OnlineHistoryCard(
    item: HistoryItem.Online,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    val entry = item.entry
    val episode = entry.lastEpisodeOrdinal?.let { "Серия ${formatEpisodeNumber(it)}" }
    val openedOnly = entry.lastWatchedAt <= 0L
    HistoryCard(
        title = entry.name,
        poster = entry.posterUrl,
        sourceLabel = entry.providerName.ifBlank { "Онлайн" },
        sourceAccent = MaterialTheme.colorScheme.primary,
        episodeLabel = episode ?: if (openedOnly) "Открыт тайтл" else "Онлайн-просмотр",
        timestamp = item.timestamp,
        progress = entry.progressFraction,
        completed = entry.lastEpisodeCompleted,
        onOpen = onOpen,
        onPlay = onPlay,
    )
}

@Composable
private fun HistoryCard(
    title: String,
    poster: String?,
    sourceLabel: String,
    sourceAccent: Color,
    episodeLabel: String,
    timestamp: Long,
    progress: Float,
    completed: Boolean,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    VaultInteractivePanel(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VaultSpacing.lg),
        role = VaultSurfaceRole.Card,
        shape = RoundedCornerShape(VaultRadius.large),
    ) {
        Row(
            modifier = Modifier.padding(VaultSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
        ) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 64.dp, height = 92.dp)
                        .clip(RoundedCornerShape(VaultRadius.medium)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 92.dp)
                        .clip(RoundedCornerShape(VaultRadius.medium))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(VaultSpacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
                ) {
                    VaultStatusPill(text = sourceLabel, accent = sourceAccent)
                    if (completed) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = "Просмотрено",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$episodeLabel · ${formatHistoryTime(timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress > 0f) {
                    WatchProgressBar(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }

            if (onPlay != null) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Продолжить")
                }
            }
        }
    }
}

private fun episodeLabel(season: Int?, episode: Double?): String = buildString {
    season?.let { append("S$it · ") }
    if (episode != null) append("Серия ${formatEpisodeNumber(episode)}") else append("Серия")
}

private fun formatEpisodeNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun formatHistoryTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
