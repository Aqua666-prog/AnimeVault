package com.sergey.animevault.ui.title

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.sergey.animevault.data.metadata.AniListMetadataCandidate
import com.sergey.animevault.data.metadata.AniListFranchiseNode
import com.sergey.animevault.data.metadata.AniListRecommendation
import com.sergey.animevault.data.metadata.FranchiseOrderMode
import com.sergey.animevault.data.metadata.AniListMetadataMatch
import com.sergey.animevault.data.metadata.MetadataMatchConfidence
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.model.OfflineOnlineLinkRow
import com.sergey.animevault.data.model.TitleMetadataRow
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.components.VaultWatchSummary
import com.sergey.animevault.ui.components.VaultAdaptiveHero
import com.sergey.animevault.ui.theme.vaultAccentFor
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultSkeletonBlock
import com.sergey.animevault.util.formatDuration
import com.sergey.animevault.util.formatEpisodeNumber

@Composable
fun TitleDetailRoute(
    viewModel: TitleDetailViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (Long) -> Unit,
    onOpenOfflineTitle: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TitleDetailEvent.OpenOfflineTitle -> onOpenOfflineTitle(event.titleId)
            }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::setPoster) },
    )
    TitleDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onPlayEpisode = onPlayEpisode,
        onChoosePoster = { coverPicker.launch(arrayOf("image/*")) },
        onToggleEpisodeSelection = viewModel::toggleEpisodeSelection,
        onClearEpisodeSelection = viewModel::clearEpisodeSelection,
        onMergeSelected = viewModel::mergeSelectedInto,
        onSeparateSelected = viewModel::separateSelected,
        onRestoreAutomaticGrouping = viewModel::restoreAutomaticGrouping,
        onOpenLinkSearch = viewModel::openOnlineLinkSearch,
        onCloseLinkSearch = viewModel::closeOnlineLinkSearch,
        onLinkQueryChange = viewModel::setOnlineLinkQuery,
        onSelectLinkProvider = viewModel::selectOnlineLinkProvider,
        onLinkRelease = viewModel::linkOnlineRelease,
        onUnlinkRelease = viewModel::unlinkOnlineRelease,
        onOpenMetadataSearch = viewModel::openMetadataSearch,
        onCloseMetadataSearch = viewModel::closeMetadataSearch,
        onMetadataQueryChange = viewModel::setMetadataSearchQuery,
        onSelectMetadata = viewModel::selectMetadata,
        onAcceptMetadataSuggestion = viewModel::acceptAutomaticMetadataSuggestion,
        onDismissMetadataSuggestion = viewModel::dismissAutomaticMetadataSuggestion,
        onClearMetadata = viewModel::clearMetadata,
        onFranchiseOrderMode = viewModel::setFranchiseOrderMode,
        onRetryFranchise = viewModel::retryFranchise,
        onOpenOnlineTitle = onOpenOnlineTitle,
        onConsumeMessage = viewModel::consumeMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailScreen(
    uiState: TitleDetailUiState,
    onBack: () -> Unit,
    onPlayEpisode: (Long) -> Unit,
    onChoosePoster: () -> Unit,
    onToggleEpisodeSelection: (Long) -> Unit,
    onClearEpisodeSelection: () -> Unit,
    onMergeSelected: (Long) -> Unit,
    onSeparateSelected: (String) -> Unit,
    onRestoreAutomaticGrouping: () -> Unit,
    onOpenLinkSearch: () -> Unit,
    onCloseLinkSearch: () -> Unit,
    onLinkQueryChange: (String) -> Unit,
    onSelectLinkProvider: (String) -> Unit,
    onLinkRelease: (OnlineReleaseCard) -> Unit,
    onUnlinkRelease: (OfflineOnlineLinkRow) -> Unit,
    onOpenMetadataSearch: () -> Unit,
    onCloseMetadataSearch: () -> Unit,
    onMetadataQueryChange: (String) -> Unit,
    onSelectMetadata: (AniListMetadataCandidate) -> Unit,
    onAcceptMetadataSuggestion: () -> Unit,
    onDismissMetadataSuggestion: () -> Unit,
    onClearMetadata: () -> Unit,
    onFranchiseOrderMode: (FranchiseOrderMode) -> Unit,
    onRetryFranchise: () -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showSeparateDialog by remember { mutableStateOf(false) }
    var separatedTitleName by remember(uiState.title?.name) {
        mutableStateOf(uiState.title?.name?.let { "$it — отдельно" }.orEmpty())
    }
    val selectionMode = uiState.selectedEpisodeIds.isNotEmpty()

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            onConsumeMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    Text(
                        text = if (selectionMode) {
                            "Выбрано: ${uiState.selectedEpisodeIds.size}"
                        } else {
                            uiState.title?.name ?: "Тайтл"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    VaultTopBarAction(
                        icon = if (selectionMode) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (selectionMode) "Отменить выбор" else "Назад",
                        onClick = if (selectionMode) onClearEpisodeSelection else onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                actions = {
                    if (selectionMode) {
                        if (uiState.groupingTargets.isNotEmpty()) {
                            VaultTopBarAction(
                                icon = Icons.Outlined.MergeType,
                                contentDescription = "Объединить в тайтл",
                                onClick = { showMergeDialog = true },
                            )
                        }
                        VaultTopBarAction(
                            icon = Icons.Outlined.VerticalSplit,
                            contentDescription = "Отделить в новый тайтл",
                            onClick = { showSeparateDialog = true },
                        )
                        VaultTopBarAction(
                            icon = Icons.Outlined.RestartAlt,
                            contentDescription = "Вернуть автоматическую группировку",
                            onClick = onRestoreAutomaticGrouping,
                        )
                    } else {
                        VaultTopBarAction(
                            icon = Icons.Outlined.Image,
                            contentDescription = "Выбрать обложку",
                            onClick = onChoosePoster,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> OfflineTitleLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            uiState.title == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                VaultEmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "Тайтл не найден",
                    body = "Запись исчезла из медиатеки или была изменена во время пересканирования.",
                )
            }

            else -> {
                val effectivePoster = uiState.title.posterUri
                    ?: uiState.metadata?.posterUrl
                    ?: uiState.onlineLinks.firstNotNullOfOrNull(OfflineOnlineLinkRow::posterUrl)
                val heroAccent = vaultAccentFor(effectivePoster ?: uiState.title.name)
                LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    VaultAdaptiveHero(
                        poster = effectivePoster,
                        seed = effectivePoster ?: uiState.title.name,
                        title = uiState.title.name,
                        posterContentDescription = "Обложка ${uiState.title.name}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        details = {
                            VaultStatusPill("ОФЛАЙН", accent = heroAccent)
                            Spacer(Modifier.height(9.dp))
                            Text(
                                text = "${uiState.episodes.size} серий в локальной медиатеке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            uiState.metadata?.let { metadata ->
                                val metadataLine = listOfNotNull(
                                    metadata.year?.toString(),
                                    metadataFormatLabel(metadata.format),
                                    metadata.averageScore?.let { "AniList $it/100" },
                                ).joinToString(" · ")
                                if (metadataLine.isNotBlank()) {
                                    Text(
                                        text = metadataLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val completedCount = uiState.episodes.count { it.isCompleted }
                            if (completedCount > 0) {
                                Text(
                                    text = "$completedCount просмотрено",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            uiState.continueEpisodeId?.let { episodeId ->
                                Button(
                                    onClick = { onPlayEpisode(episodeId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Продолжить просмотр")
                                }
                                Spacer(Modifier.height(9.dp))
                            }
                            OutlinedButton(
                                onClick = onOpenLinkSearch,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Icon(Icons.Outlined.Link, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Связать с онлайн-релизом")
                            }
                            Spacer(Modifier.height(9.dp))
                            OutlinedButton(
                                onClick = onOpenMetadataSearch,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Icon(Icons.Outlined.Info, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(if (uiState.metadata == null) "Найти метаданные" else "Обновить метаданные")
                            }
                        },
                    )
                }
                item {
                    VaultWatchSummary(
                        total = uiState.episodes.size,
                        completed = uiState.episodes.count { it.isCompleted },
                        inProgress = uiState.episodes.count { !it.isCompleted && it.positionMs > 0L },
                        accent = heroAccent,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (uiState.metadata == null && (
                        uiState.metadataSearch.autoChecking ||
                            uiState.metadataSearch.autoSuggestion != null
                        )
                ) {
                    item {
                        AutomaticMetadataMatchCard(
                            state = uiState.metadataSearch,
                            onAccept = onAcceptMetadataSuggestion,
                            onDismiss = onDismissMetadataSuggestion,
                            onChooseAnother = onOpenMetadataSearch,
                        )
                    }
                }
                uiState.metadata?.let { metadata ->
                    item {
                        OfflineMetadataCard(
                            metadata = metadata,
                            onClear = onClearMetadata,
                        )
                    }
                }
                if (uiState.metadata != null) {
                    item {
                        FranchiseSection(
                            state = uiState.franchise,
                            onOrderMode = onFranchiseOrderMode,
                            onRetry = onRetryFranchise,
                        )
                    }
                }
                if (uiState.onlineLinks.isNotEmpty()) {
                    item {
                        Text(
                            text = "Связанные онлайн-релизы",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    items(
                        items = uiState.onlineLinks,
                        key = { "${it.providerId}|${it.onlineReleaseId}" },
                    ) { link ->
                        OnlineLinkCard(
                            link = link,
                            onOpen = { onOpenOnlineTitle(link.providerId, link.onlineReleaseId) },
                            onDelete = { onUnlinkRelease(link) },
                        )
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            text = "Серии",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Удерживайте серию, чтобы объединить или отделить файлы",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (uiState.episodes.isEmpty()) {
                    item {
                        Text(
                            text = "В этой папке больше нет доступных видео.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(uiState.episodes, key = EpisodeRow::id) { episode ->
                        EpisodeCard(
                            episode = episode,
                            selected = episode.id in uiState.selectedEpisodeIds,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) onToggleEpisodeSelection(episode.id)
                                else onPlayEpisode(episode.id)
                            },
                            onLongClick = { onToggleEpisodeSelection(episode.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            }
        }
    }

    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Объединить в тайтл") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(uiState.groupingTargets, key = { it.id }) { target ->
                        Surface(
                            onClick = {
                                onMergeSelected(target.id)
                                showMergeDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                        ) {
                            Text(target.name, modifier = Modifier.padding(vertical = 14.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (showSeparateDialog) {
        AlertDialog(
            onDismissRequest = { showSeparateDialog = false },
            title = { Text("Отделить выбранные серии") },
            text = {
                OutlinedTextField(
                    value = separatedTitleName,
                    onValueChange = { separatedTitleName = it },
                    label = { Text("Название нового тайтла") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = separatedTitleName.isNotBlank(),
                    onClick = {
                        onSeparateSelected(separatedTitleName)
                        showSeparateDialog = false
                    },
                ) { Text("Отделить") }
            },
            dismissButton = {
                TextButton(onClick = { showSeparateDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (uiState.linkSearch.visible) {
        OnlineLinkSearchDialog(
            state = uiState.linkSearch,
            onDismiss = onCloseLinkSearch,
            onQueryChange = onLinkQueryChange,
            onSelectProvider = onSelectLinkProvider,
            onLink = onLinkRelease,
        )
    }

    if (uiState.metadataSearch.visible) {
        MetadataSearchDialog(
            state = uiState.metadataSearch,
            onDismiss = onCloseMetadataSearch,
            onQueryChange = onMetadataQueryChange,
            onSelect = onSelectMetadata,
        )
    }
}

@Composable
private fun FranchiseSection(
    state: FranchiseUiState,
    onOrderMode: (FranchiseOrderMode) -> Unit,
    onRetry: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Франшиза", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                FranchiseUiState.Idle -> Text("Связи появятся после сопоставления AniList", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FranchiseUiState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Загружаем связи и рекомендации…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is FranchiseUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.size(7.dp))
                        Text("Повторить")
                    }
                }
                is FranchiseUiState.Ready -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FranchiseOrderMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.orderMode == mode,
                                onClick = { onOrderMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    val ordered = state.graph.ordered(state.orderMode)
                    if (ordered.size <= 1) {
                        Text("Связанных аниме в AniList не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(ordered, key = { "franchise:${it.id}" }) { node ->
                                FranchiseMediaCard(
                                    media = node,
                                    root = node.id == state.graph.rootId,
                                    onOpen = { node.siteUrl?.let(uriHandler::openUri) },
                                )
                            }
                        }
                    }
                    if (state.graph.recommendations.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Text("Похожее", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.graph.recommendations.take(10), key = { "rec:${it.media.id}" }) { recommendation ->
                                RecommendationCard(recommendation) { recommendation.media.siteUrl?.let(uriHandler::openUri) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FranchiseMediaCard(
    media: AniListFranchiseNode,
    root: Boolean,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.width(126.dp),
        shape = RoundedCornerShape(17.dp),
        color = if (root) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column {
            AsyncImage(
                model = media.posterUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(166.dp),
            )
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(media.year?.toString(), media.format).joinToString(" · ").ifBlank { if (root) "Текущий" else "AniList" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: AniListRecommendation, onOpen: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AsyncImage(
            model = recommendation.media.posterUrl,
            contentDescription = recommendation.media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(15.dp)),
        )
        Text(recommendation.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        if (recommendation.rating > 0) {
            Text("Рекомендация +${recommendation.rating}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfflineTitleLoading(
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
                        modifier = Modifier.width(104.dp).height(150.dp),
                        shape = RoundedCornerShape(18.dp),
                    )
                    Spacer(Modifier.width(15.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultSkeletonBlock(Modifier.fillMaxWidth().height(24.dp), RoundedCornerShape(8.dp))
                        VaultSkeletonBlock(Modifier.fillMaxWidth(0.72f).height(24.dp), RoundedCornerShape(8.dp))
                        VaultSkeletonBlock(Modifier.width(72.dp).height(24.dp), RoundedCornerShape(50))
                        VaultSkeletonBlock(Modifier.fillMaxWidth(0.64f).height(11.dp), RoundedCornerShape(50))
                    }
                }
            }
        }
        items(4) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    VaultSkeletonBlock(Modifier.size(46.dp), RoundedCornerShape(15.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultSkeletonBlock(Modifier.fillMaxWidth(0.48f).height(14.dp), RoundedCornerShape(50))
                        VaultSkeletonBlock(Modifier.fillMaxWidth().height(10.dp), RoundedCornerShape(50))
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: EpisodeRow,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.76f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    selected -> Icons.Outlined.CheckCircle
                    episode.isCompleted -> Icons.Outlined.CheckCircle
                    episode.positionMs > 0L -> Icons.Outlined.PlayArrow
                    else -> Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (selected || episode.isCompleted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = episodeLabel(episode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = episode.fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(7.dp))
                WatchProgressBar(
                    progress = episode.progressFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = if (selectionMode && selected) "Выбрано" else formatDuration(episode.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnlineLinkCard(
    link: OfflineOnlineLinkRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            link.posterUrl?.let { poster ->
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(46.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = link.onlineTitleName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(link.providerId)
                        link.malId?.let { append(" · MAL $it") }
                        link.kodikId?.let { append(" · Kodik $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить связь")
            }
        }
    }
}

@Composable
private fun OnlineLinkSearchDialog(
    state: OnlineLinkSearchUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onLink: (OnlineReleaseCard) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Связать с онлайн-релизом") },
        text = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.providers, key = { it.id }) { provider ->
                        FilterChip(
                            selected = provider.id == state.selectedProviderId,
                            onClick = { onSelectProvider(provider.id) },
                            label = { Text(provider.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = { Text("Название аниме") },
                    singleLine = true,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 390.dp)
                        .padding(top = 8.dp),
                ) {
                    when {
                        state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.errorMessage != null -> Text(
                            text = state.errorMessage,
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        state.results.isEmpty() -> Text(
                            text = "Введите название и выберите подходящий релиз.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                items = state.results,
                                key = { "${it.providerId}|${it.id}" },
                            ) { release ->
                                Surface(
                                    onClick = { onLink(release) },
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 1.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        release.posterUrl?.let { poster ->
                                            AsyncImage(
                                                model = poster,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .width(42.dp)
                                                    .height(58.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = release.name,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = listOfNotNull(
                                                    release.providerName,
                                                    release.year?.toString(),
                                                    release.type,
                                                ).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.Outlined.Link, contentDescription = "Связать")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun AutomaticMetadataMatchCard(
    state: MetadataSearchUiState,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        ),
    ) {
        when {
            state.autoChecking -> Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Умное сопоставление", fontWeight = FontWeight.Bold)
                    Text(
                        "Сверяем название, серии и связанные идентификаторы с AniList…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.autoSuggestion != null -> {
                val match = requireNotNull(state.autoSuggestion)
                val candidate = match.candidate
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        candidate.posterUrl?.let { poster ->
                            AsyncImage(
                                model = poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(11.dp)),
                            )
                            Spacer(Modifier.width(13.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Похоже, это ${candidate.canonicalTitle}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = metadataMatchLabel(match),
                                style = MaterialTheme.typography.labelLarge,
                                color = metadataMatchColor(match.confidence),
                            )
                            if (match.reasons.isNotEmpty()) {
                                Text(
                                    text = match.reasons.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "Скрыть предложение")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                            Spacer(Modifier.size(7.dp))
                            Text("Это оно")
                        }
                        OutlinedButton(onClick = onChooseAnother, modifier = Modifier.weight(1f)) {
                            Text("Другой вариант")
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun OfflineMetadataCard(
    metadata: TitleMetadataRow,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Метаданные",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "AniList · ID ${metadata.externalId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить метаданные")
                }
            }
            metadata.canonicalTitle?.takeIf(String::isNotBlank)?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            metadata.englishTitle
                ?.takeIf { it.isNotBlank() && it != metadata.canonicalTitle }
                ?.let { english ->
                    Text(
                        text = english,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            val facts = listOfNotNull(
                metadata.year?.toString(),
                metadataFormatLabel(metadata.format),
                metadata.episodeCount?.let { "$it эп." },
                metadata.averageScore?.let { "$it/100" },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (metadata.genreList.isNotEmpty()) {
                Text(
                    text = metadata.genreList.take(6).joinToString(" · "),
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            metadata.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 12.dp),
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetadataSearchDialog(
    state: MetadataSearchUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (AniListMetadataCandidate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Метаданные AniList") },
        text = {
            Column {
                Text(
                    text = "Поиск выполняется по запросу. В базу AnimeVault попадёт только выбранная карточка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = { Text("Название аниме") },
                    singleLine = true,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 210.dp, max = 440.dp)
                        .padding(top = 10.dp),
                ) {
                    when {
                        state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.errorMessage != null -> Text(
                            text = state.errorMessage,
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.results.isEmpty() -> Text(
                            text = "Введите название, затем выберите точное совпадение.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.results, key = { it.candidate.anilistId }) { match ->
                                val candidate = match.candidate
                                Surface(
                                    onClick = { onSelect(candidate) },
                                    shape = RoundedCornerShape(15.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        candidate.posterUrl?.let { poster ->
                                            AsyncImage(
                                                model = poster,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .width(48.dp)
                                                    .height(68.dp)
                                                    .clip(RoundedCornerShape(9.dp)),
                                            )
                                            Spacer(Modifier.width(11.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.canonicalTitle,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            candidate.englishTitle
                                                ?.takeIf { it.isNotBlank() && it != candidate.canonicalTitle }
                                                ?.let { english ->
                                                    Text(
                                                        text = english,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            Text(
                                                text = metadataMatchLabel(match),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = metadataMatchColor(match.confidence),
                                            )
                                            Text(
                                                text = listOfNotNull(
                                                    candidate.year?.toString(),
                                                    metadataFormatLabel(candidate.format),
                                                    candidate.episodeCount?.let { "$it эп." },
                                                    candidate.averageScore?.let { "$it/100" },
                                                ).joinToString(" · ").ifBlank { "AniList ${candidate.anilistId}" },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            if (match.reasons.isNotEmpty()) {
                                                Text(
                                                    text = match.reasons.take(2).joinToString(" · "),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

private fun metadataMatchLabel(match: AniListMetadataMatch): String = when (match.confidence) {
    MetadataMatchConfidence.VERIFIED -> "Проверено · ${match.score}%"
    MetadataMatchConfidence.HIGH -> "Высокая уверенность · ${match.score}%"
    MetadataMatchConfidence.MEDIUM -> "Возможное совпадение · ${match.score}%"
    MetadataMatchConfidence.LOW -> "Слабое совпадение · ${match.score}%"
}

@Composable
private fun metadataMatchColor(confidence: MetadataMatchConfidence): Color = when (confidence) {
    MetadataMatchConfidence.VERIFIED -> MaterialTheme.colorScheme.primary
    MetadataMatchConfidence.HIGH -> MaterialTheme.colorScheme.tertiary
    MetadataMatchConfidence.MEDIUM -> MaterialTheme.colorScheme.secondary
    MetadataMatchConfidence.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun metadataFormatLabel(format: String?): String? = when (format) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "MOVIE" -> "Фильм"
    "SPECIAL" -> "Спешл"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Музыка"
    else -> format?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
}

private fun episodeLabel(episode: EpisodeRow): String = buildString {
    if (episode.seasonNumber != null) append("S${episode.seasonNumber} · ")
    if (episode.episodeNumber != null) {
        append("Серия ${formatEpisodeNumber(episode.episodeNumber)}")
    } else {
        append("Видео")
    }
}
