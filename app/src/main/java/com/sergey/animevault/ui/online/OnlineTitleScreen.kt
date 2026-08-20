package com.sergey.animevault.ui.online

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.data.metadata.AnimeThemeInfo
import com.sergey.animevault.data.metadata.AnimeThemeKind
import com.sergey.animevault.data.metadata.AnimeThemeSong
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.VaultFilterChip
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultSkeletonBlock
import com.sergey.animevault.ui.components.vaultClickable
import com.sergey.animevault.util.formatDuration
import com.sergey.animevault.util.formatEpisodeNumber
import com.sergey.animevault.ui.title.UnifiedTitleOverview
import com.sergey.animevault.ui.title.UnifiedTitleSourceUi
import com.sergey.animevault.ui.title.UnifiedTitleUiModel

@Composable
fun OnlineTitleRoute(
    viewModel: OnlineTitleViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnlineTitleScreen(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::retry,
        onRetryThemes = viewModel::retryThemes,
        onPlayEpisode = onPlayEpisode,
        onOpenLocalTitle = onOpenLocalTitle,
        onOpenDownloads = onOpenDownloads,
        onSelectTranslation = viewModel::selectTranslation,
        onToggleFavorite = viewModel::toggleFavorite,
        onDownloadEpisode = viewModel::downloadEpisode,
        onPauseDownload = viewModel::pauseDownload,
        onResumeDownload = viewModel::resumeDownload,
        onRemoveDownload = viewModel::removeDownload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTitleScreen(
    uiState: OnlineTitleUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryThemes: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onSelectTranslation: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    Text(
                        text = uiState.release?.name ?: "Онлайн-релиз",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    VaultTopBarAction(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                actions = {
                    if (uiState.release != null) {
                        VaultTopBarAction(
                            icon = Icons.Outlined.DownloadForOffline,
                            contentDescription = "Открыть скачивания",
                            onClick = onOpenDownloads,
                        )
                        VaultTopBarAction(
                            icon = if (uiState.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (uiState.isFavorite) "Убрать из избранного" else "Добавить в избранное",
                            onClick = onToggleFavorite,
                            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> OnlineTitleLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            uiState.release == null -> OnlineTitleError(
                message = uiState.errorMessage ?: "Релиз не найден",
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> {
                val release = uiState.release
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        val linkedLocal = uiState.linkedLocalTitle
                        val onlineCompleted = release.episodes.count { episode ->
                            uiState.progress[episode.id]?.isCompleted == true
                        }
                        val onlineInProgress = release.episodes.count { episode ->
                            val progress = uiState.progress[episode.id]
                            progress != null && !progress.isCompleted && progress.positionMs > 0L
                        }
                        UnifiedTitleOverview(
                            model = UnifiedTitleUiModel(
                                title = release.name,
                                secondaryTitle = release.englishName,
                                poster = release.posterUrl ?: linkedLocal?.posterUri,
                                year = release.year,
                                type = release.type,
                                season = release.season,
                                totalEpisodes = maxOf(release.episodes.size, linkedLocal?.episodeCount ?: 0),
                                completedEpisodes = maxOf(onlineCompleted, linkedLocal?.completedCount ?: 0),
                                inProgressEpisodes = maxOf(onlineInProgress, linkedLocal?.inProgressCount ?: 0),
                                localTitleId = linkedLocal?.titleId,
                                localTitleName = linkedLocal?.titleName,
                                localEpisodeCount = linkedLocal?.episodeCount ?: 0,
                                onlineSources = listOf(
                                    UnifiedTitleSourceUi(
                                        providerId = release.providerId,
                                        releaseId = release.id,
                                        name = release.providerName,
                                        isCurrent = true,
                                    ),
                                ),
                                isOngoing = release.isOngoing,
                            ),
                            primaryActionLabel = uiState.continueEpisodeId?.let { episodeId ->
                                if ((uiState.progress[episodeId]?.positionMs ?: 0L) > 0L) {
                                    "Продолжить просмотр"
                                } else {
                                    "Смотреть"
                                }
                            },
                            onPrimaryAction = uiState.continueEpisodeId?.let { episodeId ->
                                { onPlayEpisode(episodeId) }
                            },
                            onOpenLocal = onOpenLocalTitle,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    release.notification?.let { notification ->
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(notification, modifier = Modifier.padding(14.dp))
                            }
                        }
                    }
                    if (release.isBlocked) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Text(
                                    text = "Источник ограничил онлайн-просмотр этого релиза.",
                                    modifier = Modifier.padding(14.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                    if (uiState.translationOptions.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    text = "Озвучка и субтитры",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                LazyRow(
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 16.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    item {
                                        VaultFilterChip(
                                            selected = uiState.selectedTranslationKey == null,
                                            onClick = { onSelectTranslation(null) },
                                            label = { Text("Авто") },
                                        )
                                    }
                                    items(
                                        items = uiState.translationOptions,
                                        key = { it.key },
                                    ) { option ->
                                        VaultFilterChip(
                                            selected = option.key == uiState.selectedTranslationKey,
                                            onClick = { onSelectTranslation(option.key) },
                                            label = { Text(option.displayName) },
                                            leadingIcon = if (option.key == uiState.selectedTranslationKey) {
                                                {
                                                    Icon(
                                                        Icons.Outlined.CheckCircle,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    release.description?.let { description ->
                        item { ExpandableDescription(description) }
                    }
                    if (uiState.isThemesLoading || uiState.themes != null || uiState.themesMessage != null) {
                        item {
                            AnimeMusicSection(
                                info = uiState.themes,
                                isLoading = uiState.isThemesLoading,
                                errorMessage = uiState.themesMessage,
                                onRetry = onRetryThemes,
                            )
                        }
                    }
                    if (release.genres.isNotEmpty()) {
                        item {
                            Text(
                                text = release.genres.joinToString(" · "),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    uiState.downloadMessage?.let { message ->
                        item {
                            Text(
                                text = message,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Серии",
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (release.episodes.isEmpty()) {
                        item {
                            Text(
                                text = "Серии пока не опубликованы.",
                                modifier = Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(release.episodes, key = OnlineEpisode::id) { episode ->
                            OnlineEpisodeCard(
                                episode = episode,
                                progress = uiState.progress[episode.id] ?: OnlineWatchProgress(),
                                download = uiState.downloadsByEpisode[episode.id],
                                onClick = { onPlayEpisode(episode.id) },
                                onDownload = { onDownloadEpisode(episode.id) },
                                onPauseDownload = { onPauseDownload(episode.id) },
                                onResumeDownload = { onResumeDownload(episode.id) },
                                onRemoveDownload = { onRemoveDownload(episode.id) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(28.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ExpandableDescription(description: String) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    val canCollapse = description.length > 520 || description.count { it == '\n' } > 5
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.66f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(
                text = "О тайтле",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                maxLines = if (expanded || !canCollapse) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (canCollapse) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (expanded) "Свернуть" else "Читать полностью")
                }
            }
        }
    }
}

@Composable
private fun AnimeMusicSection(
    info: AnimeThemeInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    var expanded by rememberSaveable(info?.malId) { mutableStateOf(false) }
    val collapsedLimit = 3
    val visibleOpenings = when {
        info == null -> emptyList()
        expanded -> info.openings
        else -> info.openings.take(collapsedLimit)
    }
    val visibleEndings = when {
        info == null -> emptyList()
        expanded -> info.endings
        else -> info.endings.take(collapsedLimit)
    }
    val hasHiddenItems = info != null && (
        info.openings.size > collapsedLimit || info.endings.size > collapsedLimit
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Музыка",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (info != null) {
                        Text(
                            text = buildString {
                                append(info.sourceLabel)
                                if (info.malId > 0L) append(" · MAL ${info.malId}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(10.dp))
            when {
                isLoading && info == null -> Text(
                    text = "Ищу названия опенингов и эндингов…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage != null && info == null -> {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
                info == null -> Unit
                info.isEmpty -> Text(
                    text = "Для этого тайтла названия OP/ED в каталоге пока не найдены.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    ThemeSongGroup(
                        title = "Опенинги",
                        kind = AnimeThemeKind.OPENING,
                        songs = visibleOpenings,
                    )
                    if (visibleOpenings.isNotEmpty() && visibleEndings.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                    }
                    ThemeSongGroup(
                        title = "Эндинги",
                        kind = AnimeThemeKind.ENDING,
                        songs = visibleEndings,
                    )
                    if (hasHiddenItems || expanded) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(
                                if (expanded) "Свернуть" else "Показать все (${info.totalCount})",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSongGroup(
    title: String,
    kind: AnimeThemeKind,
    songs: List<AnimeThemeSong>,
) {
    if (songs.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    songs.forEachIndexed { index, song ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        ThemeSongRow(song = song, kind = kind)
    }
}

@Composable
private fun ThemeSongRow(
    song: AnimeThemeSong,
    kind: AnimeThemeKind,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        ) {
            Text(
                text = when (kind) {
                    AnimeThemeKind.OPENING -> "OP ${song.number ?: ""}".trim()
                    AnimeThemeKind.ENDING -> "ED ${song.number ?: ""}".trim()
                },
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            song.artist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            song.episodeRange?.let { range ->
                Text(
                    text = range,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnlineEpisodeCard(
    episode: OnlineEpisode,
    progress: OnlineWatchProgress,
    download: DownloadEntry?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .vaultClickable(enabled = episode.hasStream, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.76f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                episode.previewUrl?.let { previewUrl ->
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.36f)),
                                ),
                            ),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.64f),
                ) {
                    Icon(
                        imageVector = when {
                            progress.isCompleted -> Icons.Outlined.CheckCircle
                            else -> Icons.Outlined.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(7.dp).size(18.dp),
                        tint = if (progress.isCompleted) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.ordinal?.let { "Серия ${formatEpisodeNumber(it)}" } ?: "Серия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                episode.name?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val bestQuality = episode.streams.mapNotNull { it.quality }.maxOrNull()
                val translations = episode.streams.mapNotNull { it.translation?.trim()?.takeIf(String::isNotBlank) }.distinct()
                val sourceNames = episode.streams.mapNotNull { it.sourceName?.trim()?.takeIf(String::isNotBlank) }.distinct()
                Text(
                    text = buildString {
                        bestQuality?.let { append("${it}p") }
                        if (translations.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(if (translations.size == 1) translations.first() else "${translations.size} озвучки")
                        }
                        sourceNames.firstOrNull()?.let { source ->
                            if (isNotEmpty()) append(" · ")
                            append(source)
                        }
                        if (isEmpty() && episode.hasStream) append("Авто качество")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.86f),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(8.dp))
                WatchProgressBar(
                    progress = progress.fraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                DownloadEpisodeAction(
                    download = download,
                    enabled = episode.hasStream,
                    onDownload = onDownload,
                    onPause = onPauseDownload,
                    onResume = onResumeDownload,
                    onRemove = onRemoveDownload,
                )
                Text(
                    text = when {
                        download?.status == DownloadStatus.COMPLETED -> "офлайн"
                        download != null && download.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED) -> "${download.progressPercent.toInt()}%"
                        episode.streams.size > 1 -> "${episode.streams.size} вариантов"
                        else -> if (episode.hasStream) formatDuration(episode.durationMs) else "нет видео"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (download?.status == DownloadStatus.COMPLETED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadEpisodeAction(
    download: DownloadEntry?,
    enabled: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val icon = when (download?.status) {
        DownloadStatus.COMPLETED -> Icons.Outlined.DownloadDone
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> Icons.Outlined.PauseCircleOutline
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> Icons.Outlined.Refresh
        DownloadStatus.REMOVING -> Icons.Outlined.DeleteOutline
        null -> Icons.Outlined.Download
    }
    val description = when (download?.status) {
        DownloadStatus.COMPLETED -> "Удалить офлайн-копию"
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> "Поставить загрузку на паузу"
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> "Продолжить загрузку"
        DownloadStatus.REMOVING -> "Удаление"
        null -> "Скачать серию"
    }
    IconButton(
        enabled = enabled && download?.status != DownloadStatus.REMOVING,
        onClick = {
            when (download?.status) {
                DownloadStatus.COMPLETED -> onRemove()
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> onPause()
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> onResume()
                DownloadStatus.REMOVING -> Unit
                null -> onDownload()
            }
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (download?.status == DownloadStatus.COMPLETED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlineTitleLoading(
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                ),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    VaultSkeletonBlock(
                        modifier = Modifier
                            .width(106.dp)
                            .height(154.dp),
                        shape = RoundedCornerShape(18.dp),
                    )
                    Spacer(Modifier.width(15.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultSkeletonBlock(Modifier.fillMaxWidth().height(24.dp), RoundedCornerShape(8.dp))
                        VaultSkeletonBlock(Modifier.fillMaxWidth(0.72f).height(24.dp), RoundedCornerShape(8.dp))
                        VaultSkeletonBlock(Modifier.fillMaxWidth(0.56f).height(12.dp), RoundedCornerShape(50))
                        Spacer(Modifier.height(10.dp))
                        VaultSkeletonBlock(Modifier.fillMaxWidth().height(44.dp), RoundedCornerShape(16.dp))
                    }
                }
            }
        }
        items(4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultSkeletonBlock(
                    modifier = Modifier
                        .width(112.dp)
                        .height(68.dp),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultSkeletonBlock(Modifier.fillMaxWidth(0.55f).height(14.dp), RoundedCornerShape(50))
                    VaultSkeletonBlock(Modifier.fillMaxWidth().height(10.dp), RoundedCornerShape(50))
                }
            }
        }
    }
}

@Composable
private fun OnlineTitleError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        VaultEmptyState(
            icon = Icons.Outlined.CloudOff,
            title = "Не удалось открыть релиз",
            body = message,
            actionLabel = "Повторить",
            onAction = onRetry,
        )
    }
}
