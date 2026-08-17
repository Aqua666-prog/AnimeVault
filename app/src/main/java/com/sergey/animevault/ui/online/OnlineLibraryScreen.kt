package com.sergey.animevault.ui.online

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.vaultClickable
import com.sergey.animevault.util.formatEpisodeNumber
import com.sergey.animevault.ui.theme.vaultAccentFor

private enum class OnlineLibraryTab(val title: String) {
    CONTINUE("Продолжить"),
    FAVORITES("Избранное"),
    HISTORY("История"),
}

@Composable
fun OnlineLibraryRoute(
    viewModel: OnlineLibraryViewModel,
    onBack: () -> Unit,
    onOpenTitle: (String, String) -> Unit,
    onPlayEpisode: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnlineLibraryScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenTitle = onOpenTitle,
        onPlayEpisode = onPlayEpisode,
        onClearHistory = viewModel::clearHistory,
        onClearFavorites = viewModel::clearFavorites,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineLibraryScreen(
    uiState: OnlineLibraryUiState,
    onBack: () -> Unit,
    onOpenTitle: (String, String) -> Unit,
    onPlayEpisode: (String, String, String) -> Unit,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(OnlineLibraryTab.CONTINUE) }
    var pendingClearTab by remember { mutableStateOf<OnlineLibraryTab?>(null) }
    val listState = rememberLazyListState()
    val entries = when (selectedTab) {
        OnlineLibraryTab.CONTINUE -> uiState.continueWatching
        OnlineLibraryTab.FAVORITES -> uiState.favorites
        OnlineLibraryTab.HISTORY -> uiState.history
    }
    LaunchedEffect(selectedTab) {
        if (entries.isNotEmpty()) listState.scrollToItem(0)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { AnimeBrandTitle("Моя медиатека") },
                    navigationIcon = {
                        VaultTopBarAction(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                            onClick = onBack,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    },
                    actions = {
                        if (selectedTab == OnlineLibraryTab.HISTORY && uiState.history.isNotEmpty()) {
                            VaultTopBarAction(
                                icon = Icons.Outlined.DeleteSweep,
                                contentDescription = "Очистить историю",
                                onClick = { pendingClearTab = OnlineLibraryTab.HISTORY },
                            )
                        }
                        if (selectedTab == OnlineLibraryTab.FAVORITES && uiState.favorites.isNotEmpty()) {
                            VaultTopBarAction(
                                icon = Icons.Outlined.DeleteSweep,
                                contentDescription = "Очистить избранное",
                                onClick = { pendingClearTab = OnlineLibraryTab.FAVORITES },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(OnlineLibraryTab.entries) { tab ->
                        val count = when (tab) {
                            OnlineLibraryTab.CONTINUE -> uiState.continueWatching.size
                            OnlineLibraryTab.FAVORITES -> uiState.favorites.size
                            OnlineLibraryTab.HISTORY -> uiState.history.size
                        }
                        FilterChip(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            label = { Text("${tab.title} · $count") },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            OnlineLibraryEmptyState(
                tab = selectedTab,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { "${it.providerId}|${it.releaseId}" }) { entry ->
                    OnlineLibraryEntryCard(
                        entry = entry,
                        showContinue = selectedTab == OnlineLibraryTab.CONTINUE,
                        onOpen = { onOpenTitle(entry.providerId, entry.releaseId) },
                        onPlay = entry.lastEpisodeId?.let { episodeId ->
                            { onPlayEpisode(entry.providerId, entry.releaseId, episodeId) }
                        },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    pendingClearTab?.let { tab ->
        val isHistory = tab == OnlineLibraryTab.HISTORY
        AlertDialog(
            onDismissRequest = { pendingClearTab = null },
            title = { Text(if (isHistory) "Очистить историю?" else "Очистить избранное?") },
            text = {
                Text(
                    if (isHistory) {
                        "Список открытых и просмотренных онлайн-тайтлов будет очищен. Избранное останется на месте."
                    } else {
                        "Все онлайн-тайтлы будут удалены из избранного. История просмотра сохранится."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isHistory) onClearHistory() else onClearFavorites()
                        pendingClearTab = null
                    },
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearTab = null }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun OnlineLibraryEntryCard(
    entry: OnlineLibraryEntry,
    showContinue: Boolean,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    val accent = remember(entry.posterUrl, entry.name) {
        vaultAccentFor(entry.posterUrl ?: entry.name)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .vaultClickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(width = 76.dp, height = 108.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (entry.posterUrl != null) {
                    AsyncImage(
                        model = entry.posterUrl,
                        contentDescription = "Обложка ${entry.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VideoLibrary, contentDescription = null, tint = accent)
                    }
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        entry.providerName,
                        entry.year?.toString(),
                        entry.type,
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.lastEpisodeOrdinal?.let { ordinal ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Серия ${formatEpisodeNumber(ordinal)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (entry.lastDurationMs > 0L) {
                    Spacer(Modifier.height(8.dp))
                    WatchProgressBar(
                        progress = entry.progressFraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        accent = accent,
                    )
                }
            }
            if (showContinue && onPlay != null) {
                Spacer(Modifier.width(9.dp))
                Surface(
                    onClick = onPlay,
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = 0.16f),
                    contentColor = accent,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Продолжить просмотр",
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineLibraryEmptyState(
    tab: OnlineLibraryTab,
    modifier: Modifier = Modifier,
) {
    val icon = when (tab) {
        OnlineLibraryTab.CONTINUE -> Icons.Outlined.PlayCircleOutline
        OnlineLibraryTab.FAVORITES -> Icons.Outlined.FavoriteBorder
        OnlineLibraryTab.HISTORY -> Icons.Outlined.History
    }
    val title = when (tab) {
        OnlineLibraryTab.CONTINUE -> "Нечего продолжать"
        OnlineLibraryTab.FAVORITES -> "Избранное пока пусто"
        OnlineLibraryTab.HISTORY -> "История пока пуста"
    }
    val description = when (tab) {
        OnlineLibraryTab.CONTINUE -> "Начните смотреть онлайн-серию, и она появится здесь вместе с сохранённой позицией."
        OnlineLibraryTab.FAVORITES -> "Нажмите сердечко на странице тайтла, чтобы сохранить его в личной медиатеке."
        OnlineLibraryTab.HISTORY -> "Открытые и просмотренные онлайн-тайтлы будут аккуратно собираться здесь."
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        VaultEmptyState(
            icon = icon,
            title = title,
            body = description,
        )
    }
}
