package com.sergey.animevault.ui.downloads

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import com.sergey.animevault.ui.title.animeTitle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalDensity
import com.sergey.animevault.ui.title.TitleSectionTab
import com.sergey.animevault.ui.components.VaultPrimaryButton
import com.sergey.animevault.ui.design.VaultRadius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.theme.vaultMotionDuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.ui.components.VaultArtwork
import com.sergey.animevault.ui.components.vaultTitleArtwork
import com.sergey.animevault.ui.theme.LocalVaultColors
import com.sergey.animevault.ui.components.VaultEmptyState
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
    val posters by viewModel.posters.collectAsStateWithLifecycle()
    DownloadsScreen(
        entries = entries,
        posters = posters,
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
    posters: Map<String, String> = emptyMap(),
) {
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var pendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = remember(entries) { summarizeDownloads(entries) }
    val filteredEntries = remember(entries, selectedFilter) { filterDownloads(entries, selectedFilter) }
    val sections = remember(entries, selectedFilter, filteredEntries) {
        if (selectedFilter == DownloadFilter.ALL) {
            listOf(DownloadFilter.ACTIVE, DownloadFilter.READY, DownloadFilter.ERRORS)
                .map { it to filterDownloads(entries, it) }
                .filter { (_, downloads) -> downloads.isNotEmpty() }
        } else {
            listOf(selectedFilter to filteredEntries)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Загрузки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            VaultEmptyState(
                title = "Пока ничего не скачано",
                body = "Откройте тайтл и скачайте нужную серию, чтобы посмотреть её без сети.",
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
                    sections.forEach { (section, downloads) ->
                        item(key = "downloads-section-${section.name}") {
                            Text(
                                text = when (section) {
                                    DownloadFilter.ACTIVE -> "Очередь загрузок"
                                    DownloadFilter.READY -> "Доступно офлайн"
                                    DownloadFilter.ERRORS -> "Требуют внимания"
                                    DownloadFilter.ALL -> "Все загрузки"
                                } + " · ${downloads.size}",
                                modifier = Modifier.padding(top = VaultSpacing.sm).semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(downloads, key = DownloadEntry::id) { entry ->
                            val id = entry.id
                            val play = remember(id, onPlay) { { onPlay(id) } }
                            val pause = remember(id, onPause) { { onPause(id) } }
                            val resume = remember(id, onResume) { { onResume(id) } }
                            val remove = remember(id) { { pendingRemovalId = id } }
                            DownloadCard(
                                entry = entry,
                                poster = posters["${entry.providerId}|${entry.releaseId}"],
                                onPlay = play,
                                onPause = pause,
                                onResume = resume,
                                onRemove = remove,
                            )
                        }
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = VaultSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(VaultSpacing.md),
    ) {
        Text("Офлайн-коллекция", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(formatBytes(overview.storedBytes), style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Объём готовых файлов · ${overview.readyCount} серий",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Metrics describe actual queue states, not a synthetic aggregate percentage.
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(VaultSpacing.xxl)) {
            listOf("Активные" to overview.runningCount, "Ожидают" to overview.waitingCount,
                "На паузе" to overview.pausedCount, "Ошибки" to overview.errorCount).forEach { (label, count) ->
                Column {
                    Text(count.toString(), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(VaultSpacing.xs),
    ) {
        DownloadFilter.entries.forEach { filter ->
            TitleSectionTab("${filter.title} · ${overview.countFor(filter)}", selected == filter) {
                onSelected(filter)
            }
        }
    }
}

@Composable
internal fun DownloadCard(
    entry: DownloadEntry,
    poster: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent by animateColorAsState(
        targetValue = downloadStatusColor(entry.status),
        animationSpec = tween(vaultMotionDuration(VaultMotion.fast)),
        label = "download-status",
    )
    val posterWidth = if (LocalDensity.current.fontScale > 1.3f) 52.dp else 72.dp
    VaultPanel(
        role = VaultSurfaceRole.Quiet,
        shape = RoundedCornerShape(VaultRadius.medium),
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(VaultSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VaultSpacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
                verticalAlignment = Alignment.Top) {
                VaultArtwork(sources = vaultTitleArtwork(providerPoster = poster),
                    modifier = Modifier.width(posterWidth).height(posterWidth * 1.5f)
                        .clip(RoundedCornerShape(VaultRadius.small)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VaultSpacing.xs)) {
                    Text(
                        text = animeTitle(entry),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = downloadMetadata(entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(VaultSpacing.xs),
                        verticalAlignment = Alignment.Top) {
                        Icon(statusIcon(entry.status), contentDescription = null, tint = accent,
                            modifier = Modifier.size(VaultSize.compactIcon))
                        Text(statusLabel(entry), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                }
            }
            if (entry.status != DownloadStatus.COMPLETED && hasMeasuredDownloadProgress(entry)) {
                WatchProgressBar(
                    progress = (entry.progressPercent / 100f).coerceIn(0f, 1f),
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().height(VaultSize.progress),
                )
            }
            downloadByteLabel(entry).takeIf(String::isNotEmpty)?.let { byteLabel ->
                Text(byteLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entry.errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VaultSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                when (entry.status) {
                    DownloadStatus.COMPLETED -> VaultPrimaryButton(
                        text = "Смотреть офлайн", onClick = onPlay,
                        modifier = Modifier.weight(1f), icon = Icons.Outlined.PlayArrow,
                    )
                    DownloadStatus.QUEUED,
                    DownloadStatus.RESOLVING,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.RETRY_WAIT,
                    DownloadStatus.VERIFYING,
                    -> DownloadTextAction("Пауза", Icons.Outlined.PauseCircleOutline, onPause, Modifier.weight(1f))
                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED,
                    DownloadStatus.MISSING,
                    -> DownloadTextAction(
                        if (entry.status == DownloadStatus.PAUSED) "Продолжить загрузку" else "Повторить загрузку",
                        Icons.Outlined.Refresh, onResume, Modifier.weight(1f),
                    )
                    DownloadStatus.REMOVING -> Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = onRemove,
                    enabled = entry.status != DownloadStatus.REMOVING,
                    modifier = Modifier.size(VaultSize.touchTarget),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить офлайн-копию",
                        tint = if (entry.status == DownloadStatus.REMOVING)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
                        else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DownloadTextAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = VaultSize.touchTarget)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(VaultSize.compactIcon))
        Spacer(Modifier.width(VaultSpacing.sm))
        Text(label)
    }
}

@Composable
private fun downloadStatusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> LocalVaultColors.current.success
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.MISSING -> MaterialTheme.colorScheme.error
    DownloadStatus.PAUSED -> LocalVaultColors.current.warning
    DownloadStatus.REMOVING -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.QUEUED,
    DownloadStatus.RESOLVING,
    DownloadStatus.DOWNLOADING,
    DownloadStatus.RETRY_WAIT,
    DownloadStatus.VERIFYING,
    -> MaterialTheme.colorScheme.primary
}

private fun statusIcon(status: DownloadStatus): ImageVector = when (status) {
    DownloadStatus.COMPLETED -> Icons.Outlined.DownloadDone
    DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline
    DownloadStatus.MISSING -> Icons.Outlined.ErrorOutline
    DownloadStatus.PAUSED -> Icons.Outlined.PauseCircleOutline
    DownloadStatus.QUEUED,
    DownloadStatus.RESOLVING,
    DownloadStatus.DOWNLOADING,
    DownloadStatus.RETRY_WAIT,
    DownloadStatus.VERIFYING,
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
    DownloadStatus.RESOLVING -> entry.diagnosticStage ?: "Поиск рабочего потока"
    DownloadStatus.RETRY_WAIT -> entry.diagnosticStage ?: "Ожидание повторной попытки"
    DownloadStatus.VERIFYING -> entry.diagnosticStage ?: "Проверка скачанного файла"
    DownloadStatus.DOWNLOADING -> buildString {
        append("Скачивается")
        if (hasMeasuredDownloadProgress(entry)) append(" · ${entry.progressPercent.toInt()}%")
        if (entry.totalItems > 1) append(" · ${entry.completedItems}/${entry.totalItems} сегментов")
        if (entry.speedBytesPerSecond > 0L) append(" · ${formatDownloadSpeed(entry.speedBytesPerSecond)}")
        entry.etaSeconds?.takeIf { it > 0L && entry.speedBytesPerSecond > 0L }?.let { append(" · осталось ${formatDownloadEta(it)}") }
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
    val actualProvider = entry.streamProviderName?.takeIf { it.isNotBlank() }
    append(" · ${actualProvider ?: entry.providerName}")
    entry.sourceHost?.takeIf(String::isNotBlank)?.let { append(" · $it") }
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


internal fun formatDownloadSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> {
        val tenths = kotlin.math.round(bytesPerSecond / (1024.0 * 1024.0) * 10.0).toInt()
        "${tenths / 10}.${tenths % 10} МБ/с"
    }
    bytesPerSecond >= 1024L -> "${kotlin.math.round(bytesPerSecond / 1024.0).toInt()} КБ/с"
    else -> "$bytesPerSecond Б/с"
}

internal fun formatDownloadEta(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}
