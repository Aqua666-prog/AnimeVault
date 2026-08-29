package com.sergey.animevault.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultFilterChip
import com.sergey.animevault.ui.components.VaultIconTile
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultSize
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultSurfaceRole

@Composable
fun DownloadsRoute(
    viewModel: DownloadsViewModel,
    onPlay: (String) -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    DownloadsScreen(
        entries = entries,
        onPlay = onPlay,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onRemove = viewModel::remove,
    )
}

@Composable
fun DownloadsScreen(
    entries: List<DownloadEntry>,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var pendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = remember(entries) { summarizeDownloads(entries) }
    val filteredEntries = remember(entries, selectedFilter) { filterDownloads(entries, selectedFilter) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { AnimeBrandTitle("Скачивания") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            VaultEmptyState(
                title = "Пока ничего не скачано",
                body = "Откройте онлайн-тайтл и нажмите значок загрузки у нужной серии. MP4 и HLS сохраняются для офлайн-просмотра.",
                modifier = Modifier.fillMaxSize().padding(padding).padding(VaultSpacing.xxl),
                icon = Icons.Outlined.Downloading,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = VaultSpacing.lg, vertical = VaultSpacing.md),
                verticalArrangement = Arrangement.spacedBy(VaultSpacing.md),
            ) {
                item(key = "downloads-overview") {
                    DownloadsOverview(overview)
                }
                item(key = "downloads-filters") {
                    DownloadFilterRow(
                        selected = selectedFilter,
                        overview = overview,
                        onSelected = { selectedFilter = it },
                    )
                }
                if (filteredEntries.isEmpty()) {
                    item(key = "downloads-filter-empty-${selectedFilter.name}") {
                        VaultEmptyState(
                            title = emptyFilterTitle(selectedFilter),
                            body = emptyFilterBody(selectedFilter),
                            modifier = Modifier.fillMaxWidth(),
                            icon = filterIcon(selectedFilter),
                        )
                    }
                } else {
                    items(filteredEntries, key = DownloadEntry::id) { entry ->
                        DownloadCard(
                            entry = entry,
                            onPlay = { onPlay(entry.id) },
                            onPause = { onPause(entry.id) },
                            onResume = { onResume(entry.id) },
                            onRemove = { pendingRemovalId = entry.id },
                        )
                    }
                }
                item(key = "downloads-bottom-space") { Spacer(Modifier.height(VaultSpacing.xxl)) }
            }
        }
    }

    pendingRemovalId?.let { downloadId ->
        val entry = entries.firstOrNull { it.id == downloadId }
        AlertDialog(
            onDismissRequest = { pendingRemovalId = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Удалить офлайн-копию?") },
            text = {
                Text(
                    entry?.let { "«${it.releaseName}» — ${it.episodeLabel}. Скачанный файл будет удалён с устройства." }
                        ?: "Скачанный файл будет удалён с устройства.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemovalId = null
                        onRemove(downloadId)
                    },
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemovalId = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun DownloadsOverview(overview: DownloadOverview) {
    val accent = MaterialTheme.colorScheme.primary
    VaultPanel(
        modifier = Modifier.fillMaxWidth(),
        role = VaultSurfaceRole.Glass,
        shape = MaterialTheme.shapes.extraLarge,
        accent = accent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.17f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.055f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(VaultSpacing.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(VaultSpacing.md)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VaultIconTile(Icons.Outlined.Storage, accent = accent)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Офлайн-хранилище",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (overview.storedBytes > 0L) {
                                "${formatBytes(overview.storedBytes)} доступно без сети"
                            } else {
                                "Загрузки под рукой даже без сети"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
                ) {
                    VaultStatusPill("Готово ${overview.readyCount}", accent = MaterialTheme.colorScheme.secondary)
                    VaultStatusPill("В процессе ${overview.activeCount}", accent = accent)
                    if (overview.errorCount > 0) {
                        VaultStatusPill("Ошибки ${overview.errorCount}", accent = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadFilterRow(
    selected: DownloadFilter,
    overview: DownloadOverview,
    onSelected: (DownloadFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
    ) {
        DownloadFilter.entries.forEach { filter ->
            VaultFilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text("${filter.title} · ${overview.countFor(filter)}") },
                leadingIcon = {
                    Icon(
                        imageVector = filterIcon(filter),
                        contentDescription = null,
                        modifier = Modifier.size(VaultSize.compactIcon),
                    )
                },
            )
        }
    }
}

@Composable
private fun DownloadCard(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = downloadStatusColor(entry.status)
    VaultPanel(
        role = if (entry.status == DownloadStatus.FAILED) VaultSurfaceRole.Accent else VaultSurfaceRole.Card,
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = VaultSpacing.md, vertical = VaultSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VaultIconTile(
                    icon = statusIcon(entry.status),
                    contentDescription = statusLabel(entry),
                    accent = accent,
                )
                Column(Modifier.weight(1f).padding(horizontal = VaultSpacing.md)) {
                    Text(
                        text = entry.releaseName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = downloadMetadata(entry),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (entry.status) {
                    DownloadStatus.COMPLETED -> IconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(VaultSize.touchTarget),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Смотреть офлайн", tint = accent)
                    }
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    -> IconButton(
                        onClick = onPause,
                        modifier = Modifier.size(VaultSize.touchTarget),
                    ) {
                        Icon(Icons.Outlined.PauseCircleOutline, contentDescription = "Пауза", tint = accent)
                    }
                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED,
                    DownloadStatus.MISSING,
                    -> IconButton(
                        onClick = onResume,
                        modifier = Modifier.size(VaultSize.touchTarget),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Продолжить загрузку", tint = accent)
                    }
                    DownloadStatus.REMOVING -> Unit
                }
                IconButton(
                    onClick = onRemove,
                    enabled = entry.status != DownloadStatus.REMOVING,
                    modifier = Modifier.size(VaultSize.touchTarget),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Удалить офлайн-копию",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (entry.status != DownloadStatus.COMPLETED) {
                WatchProgressBar(
                    progress = (entry.progressPercent / 100f).coerceIn(0f, 1f),
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().height(VaultSize.progress),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusLabel(entry),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                downloadByteLabel(entry).takeIf(String::isNotEmpty)?.let { byteLabel ->
                    Text(
                        text = byteLabel,
                        modifier = Modifier.padding(start = VaultSpacing.sm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            entry.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun downloadStatusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.MISSING -> MaterialTheme.colorScheme.error
    DownloadStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.REMOVING -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.QUEUED,
    DownloadStatus.DOWNLOADING,
    -> MaterialTheme.colorScheme.primary
}

private fun statusIcon(status: DownloadStatus): ImageVector = when (status) {
    DownloadStatus.COMPLETED -> Icons.Outlined.DownloadDone
    DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline
    DownloadStatus.MISSING -> Icons.Outlined.ErrorOutline
    DownloadStatus.PAUSED -> Icons.Outlined.PauseCircleOutline
    DownloadStatus.QUEUED,
    DownloadStatus.DOWNLOADING,
    DownloadStatus.REMOVING,
    -> Icons.Outlined.Downloading
}

private fun filterIcon(filter: DownloadFilter): ImageVector = when (filter) {
    DownloadFilter.ALL -> Icons.Outlined.Storage
    DownloadFilter.ACTIVE -> Icons.Outlined.Downloading
    DownloadFilter.READY -> Icons.Outlined.DownloadDone
    DownloadFilter.ERRORS -> Icons.Outlined.ErrorOutline
}

private fun emptyFilterTitle(filter: DownloadFilter): String = when (filter) {
    DownloadFilter.ALL -> "Пока ничего не скачано"
    DownloadFilter.ACTIVE -> "Нет текущих загрузок"
    DownloadFilter.READY -> "Нет готовых серий"
    DownloadFilter.ERRORS -> "Ошибок нет"
}

private fun emptyFilterBody(filter: DownloadFilter): String = when (filter) {
    DownloadFilter.ALL -> "Загрузите серию из онлайн-каталога, чтобы смотреть её без сети."
    DownloadFilter.ACTIVE -> "Все задания завершены или ожидают повторного запуска."
    DownloadFilter.READY -> "Завершённые офлайн-копии появятся здесь."
    DownloadFilter.ERRORS -> "Все загрузки обработаны без сбоев."
}

internal fun statusLabel(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.QUEUED -> entry.diagnosticStage ?: "В очереди"
    DownloadStatus.DOWNLOADING -> buildString {
        append("Скачивается · ${entry.progressPercent.toInt()}%")
        if (entry.totalItems > 1) append(" · ${entry.completedItems}/${entry.totalItems} сегментов")
    }
    DownloadStatus.PAUSED -> entry.diagnosticStage ?: "Пауза"
    DownloadStatus.COMPLETED -> "Доступно офлайн"
    DownloadStatus.FAILED -> entry.diagnosticStage ?: "Ошибка загрузки"
    DownloadStatus.MISSING -> entry.diagnosticStage ?: "Офлайн-файл отсутствует"
    DownloadStatus.REMOVING -> entry.diagnosticStage ?: "Удаление…"
}

private fun downloadMetadata(entry: DownloadEntry): String = buildString {
    append(entry.episodeLabel)
    entry.quality?.let { append(" · ${it}p") }
    entry.translation?.takeIf(String::isNotBlank)?.let { append(" · $it") }
    append(" · ${entry.providerName}")
}

private fun downloadByteLabel(entry: DownloadEntry): String = buildString {
    if (entry.bytesDownloaded > 0L) append(formatBytes(entry.bytesDownloaded))
    if (entry.contentLength > 0L) {
        if (isNotEmpty()) append(" / ")
        append(formatBytes(entry.contentLength))
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    if (unit == 0) return "$bytes ${units[unit]}"
    val tenths = kotlin.math.round(value * 10.0).toInt()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0) "$whole ${units[unit]}" else "$whole.$fraction ${units[unit]}"
}
