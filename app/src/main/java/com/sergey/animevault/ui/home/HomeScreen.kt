package com.sergey.animevault.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultGlassCard
import com.sergey.animevault.ui.components.VaultSectionHeader
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.theme.vaultAccentFor
import com.sergey.animevault.util.formatEpisodeNumber

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        onOpenOffline = onOpenOffline,
        onOpenOnline = onOpenOnline,
        onOpenSettings = onOpenSettings,
        onOpenLocalTitle = onOpenLocalTitle,
        onPlayLocalEpisode = onPlayLocalEpisode,
        onOpenOnlineTitle = onOpenOnlineTitle,
        onPlayOnlineEpisode = onPlayOnlineEpisode,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
) {
    val isEmpty = uiState.localTitleCount == 0 &&
        uiState.continueWatching.isEmpty() &&
        uiState.onlineFavorites.isEmpty()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { AnimeBrandTitle("Главная") },
                actions = {
                    VaultTopBarAction(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "home-summary") {
                HomeSummary(
                    titleCount = uiState.localTitleCount,
                    episodeCount = uiState.localEpisodeCount,
                    completedCount = uiState.completedEpisodeCount,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item(key = "home-actions") {
                HomeQuickActions(
                    onOpenOffline = onOpenOffline,
                    onOpenOnline = onOpenOnline,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (uiState.localEpisodeCount > 0L || uiState.insights.onlineHistoryCount > 0) {
                item(key = "home-insights") {
                    HomeInsights(
                        insights = uiState.insights,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (isEmpty) {
                item(key = "home-empty") {
                    VaultEmptyState(
                        icon = Icons.Outlined.Movie,
                        title = "Дом пока тих",
                        body = "Добавьте папку с аниме или откройте онлайн-каталог. Здесь появятся продолжение просмотра и свежие тайтлы.",
                        actionLabel = "Открыть медиатеку",
                        onAction = onOpenOffline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (uiState.continueWatching.isNotEmpty()) {
                item(key = "continue-header") {
                    VaultSectionHeader(
                        title = "Продолжить просмотр",
                        supporting = "Последние незавершённые серии с устройства и онлайн",
                    )
                }
                item(key = "continue-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = uiState.continueWatching,
                            key = HomeContinueItem::stableKey,
                        ) { item ->
                            ContinueWatchingCard(
                                item = item,
                                onClick = {
                                    when (item) {
                                        is HomeContinueItem.Local -> onPlayLocalEpisode(item.episodeId)
                                        is HomeContinueItem.Online -> onPlayOnlineEpisode(
                                            item.providerId,
                                            item.releaseId,
                                            item.episodeId,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (uiState.recentlyAdded.isNotEmpty()) {
                item(key = "recent-header") {
                    VaultSectionHeader(
                        title = "Недавно добавлено",
                        supporting = "Свежие тайтлы из локальной медиатеки",
                    )
                }
                item(key = "recent-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.recentlyAdded, key = LibraryTitleRow::id) { title ->
                            RecentLocalTitleCard(
                                title = title,
                                onClick = { onOpenLocalTitle(title.id) },
                            )
                        }
                    }
                }
            }

            if (uiState.onlineFavorites.isNotEmpty()) {
                item(key = "favorites-header") {
                    VaultSectionHeader(
                        title = "Избранное онлайн",
                        supporting = "Быстрый доступ к сохранённым релизам",
                    )
                }
                item(key = "favorites-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = uiState.onlineFavorites,
                            key = { entry -> "${entry.providerId}|${entry.releaseId}" },
                        ) { entry ->
                            OnlineFavoriteCard(
                                entry = entry,
                                onClick = { onOpenOnlineTitle(entry.providerId, entry.releaseId) },
                            )
                        }
                    }
                }
            }

            item(key = "nav-padding") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun HomeSummary(
    titleCount: Int,
    episodeCount: Long,
    completedCount: Long,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    VaultGlassCard(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Ваша аниме-медиатека",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (titleCount == 0) {
                        "Готова принять первую коллекцию"
                    } else {
                        "$titleCount тайтлов · $episodeCount серий на устройстве"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episodeCount > 0L) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultStatusPill("Просмотрено $completedCount", accent = accent)
                        VaultStatusPill(
                            "Осталось ${(episodeCount - completedCount).coerceAtLeast(0L)}",
                            accent = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeInsights(
    insights: LibraryInsights,
    modifier: Modifier = Modifier,
) {
    VaultGlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Статистика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InsightCell(
                    icon = Icons.Outlined.Schedule,
                    value = formatWatchTime(insights.watchedTimeMs),
                    label = "просмотрено",
                    modifier = Modifier.weight(1f),
                )
                InsightCell(
                    icon = Icons.Outlined.Movie,
                    value = "${insights.completionPercent}%",
                    label = "коллекции закрыто",
                    modifier = Modifier.weight(1f),
                )
                InsightCell(
                    icon = Icons.Outlined.Storage,
                    value = formatCompactBytes(insights.totalBytes),
                    label = "локально",
                    modifier = Modifier.weight(1f),
                )
            }
            if (insights.reclaimableBytes > 0L || insights.onlineHistoryCount > 0) {
                Text(
                    text = buildString {
                        if (insights.reclaimableBytes > 0L) append("Просмотренные файлы: ${formatCompactBytes(insights.reclaimableBytes)}")
                        if (insights.reclaimableBytes > 0L && insights.onlineHistoryCount > 0) append(" · ")
                        if (insights.onlineHistoryCount > 0) append("Онлайн-история: ${insights.onlineHistoryCount}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InsightCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatWatchTime(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60L
    return if (hours > 0) "${hours} ч" else "${minutes} мин"
}

internal fun formatCompactBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(java.util.Locale.US, "%.1f ГБ", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format(java.util.Locale.US, "%.0f МБ", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format(java.util.Locale.US, "%.0f КБ", value / 1024.0)
        else -> "${value.toLong()} Б"
    }
}

@Composable
private fun HomeQuickActions(
    onOpenOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeQuickAction(
            title = "Медиатека",
            subtitle = "Файлы и папки",
            icon = Icons.Outlined.FolderOpen,
            onClick = onOpenOffline,
            modifier = Modifier.weight(1f),
        )
        HomeQuickAction(
            title = "Онлайн",
            subtitle = "Каталог источников",
            icon = Icons.Outlined.Cloud,
            onClick = onOpenOnline,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeQuickAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: HomeContinueItem,
    onClick: () -> Unit,
) {
    val accent = remember(item.stableKey) { vaultAccentFor(item.stableKey) }
    Surface(
        modifier = Modifier.width(270.dp),
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
            ) {
                PosterArtwork(
                    posterUri = item.posterUri,
                    title = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.58f)),
                            ),
                        ),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.62f),
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Продолжить",
                        modifier = Modifier.padding(8.dp).size(22.dp),
                        tint = Color.White,
                    )
                }
            }
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    continueSubtitle(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                WatchProgressBar(
                    progress = item.progressFraction,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentLocalTitleCard(
    title: LibraryTitleRow,
    onClick: () -> Unit,
) {
    val accent = remember(title.id) { vaultAccentFor("local:${title.id}") }
    Surface(
        modifier = Modifier.width(138.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Column {
            PosterArtwork(
                posterUri = title.posterUri,
                title = title.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${title.episodeCount} серий",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnlineFavoriteCard(
    entry: OnlineLibraryEntry,
    onClick: () -> Unit,
) {
    val key = "${entry.providerId}:${entry.releaseId}"
    val accent = remember(key) { vaultAccentFor(key) }
    Surface(
        modifier = Modifier.width(138.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Column {
            Box {
                PosterArtwork(
                    posterUri = entry.posterUrl,
                    title = entry.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    contentScale = ContentScale.Crop,
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Icon(
                        Icons.Outlined.Favorite,
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp).size(16.dp),
                        tint = Color.White,
                    )
                }
            }
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PosterArtwork(
    posterUri: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (!posterUri.isNullOrBlank()) {
            AsyncImage(
                model = posterUri,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            Text(
                text = title.trim().firstOrNull()?.uppercase() ?: "A",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
        }
    }
}

private fun continueSubtitle(item: HomeContinueItem): String = when (item) {
    is HomeContinueItem.Local -> buildString {
        item.seasonNumber?.let { append("Сезон $it · ") }
        append(
            item.episodeNumber?.let { "Серия ${formatEpisodeNumber(it)}" }
                ?: "Локальная серия",
        )
    }
    is HomeContinueItem.Online -> buildString {
        item.episodeOrdinal?.let { append("Серия ${formatEpisodeNumber(it)} · ") }
        append(item.providerName)
    }
}
