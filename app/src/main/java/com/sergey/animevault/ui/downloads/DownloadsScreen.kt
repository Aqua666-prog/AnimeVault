package com.sergey.animevault.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.design.VaultPanel
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
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Скачивания") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            VaultEmptyState(
                title = "Пока ничего не скачано",
                body = "Откройте онлайн-тайтл и нажмите значок загрузки у нужной серии. MP4 и HLS сохраняются для офлайн-просмотра.",
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                icon = Icons.Outlined.Downloading,
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = DownloadEntry::id) { entry ->
                DownloadCard(
                    entry = entry,
                    onPlay = { onPlay(entry.id) },
                    onPause = { onPause(entry.id) },
                    onResume = { onResume(entry.id) },
                    onRemove = { onRemove(entry.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
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
    VaultPanel(role = VaultSurfaceRole.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.status == DownloadStatus.COMPLETED) Icons.Outlined.DownloadDone else Icons.Outlined.Downloading,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = entry.releaseName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = buildString {
                            append(entry.episodeLabel)
                            entry.quality?.let { append(" · ${it}p") }
                            entry.translation?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                            append(" · ${entry.providerName}")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (entry.status) {
                    DownloadStatus.COMPLETED -> IconButton(onClick = onPlay) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Смотреть офлайн")
                    }
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.PauseCircleOutline, contentDescription = "Пауза")
                    }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Продолжить загрузку")
                    }
                    DownloadStatus.REMOVING -> Unit
                }
                IconButton(onClick = onRemove, enabled = entry.status != DownloadStatus.REMOVING) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить")
                }
            }
            if (entry.status != DownloadStatus.COMPLETED) {
                WatchProgressBar(
                    progress = (entry.progressPercent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = statusLabel(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val byteLabel = buildString {
                    if (entry.bytesDownloaded > 0L) append(formatBytes(entry.bytesDownloaded))
                    if (entry.contentLength > 0L) {
                        if (isNotEmpty()) append(" / ")
                        append(formatBytes(entry.contentLength))
                    }
                }
                if (byteLabel.isNotEmpty()) {
                    Text(byteLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            entry.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun statusLabel(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.QUEUED -> entry.diagnosticStage ?: "В очереди"
    DownloadStatus.DOWNLOADING -> buildString {
        append("Скачивается · ${entry.progressPercent.toInt()}%")
        if (entry.totalItems > 1) append(" · ${entry.completedItems}/${entry.totalItems} сегментов")
    }
    DownloadStatus.PAUSED -> entry.diagnosticStage ?: "Пауза"
    DownloadStatus.COMPLETED -> "Доступно офлайн"
    DownloadStatus.FAILED -> entry.diagnosticStage ?: "Ошибка загрузки"
    DownloadStatus.REMOVING -> entry.diagnosticStage ?: "Удаление…"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    if (unit == 0) return "${bytes} ${units[unit]}"
    val tenths = kotlin.math.round(value * 10.0).toInt()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0) "$whole ${units[unit]}" else "$whole.$fraction ${units[unit]}"
}
