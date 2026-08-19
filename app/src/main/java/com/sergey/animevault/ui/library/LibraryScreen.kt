package com.sergey.animevault.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.ui.components.LibrarySection
import com.sergey.animevault.ui.components.LibrarySectionTabs
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultSearchField
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.vaultClickable
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.theme.vaultAccentFor

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onOpenTitle: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnline: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(
        uiState = uiState,
        onQueryChange = viewModel::setQuery,
        onSortChange = viewModel::setSort,
        onCollectionChange = viewModel::setCollection,
        onFolderSelected = viewModel::addFolder,
        onRescan = viewModel::rescanAll,
        onDismissScanMessage = viewModel::dismissScanMessage,
        onOpenTitle = onOpenTitle,
        onOpenSettings = onOpenSettings,
        onOpenOnline = onOpenOnline,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onCollectionChange: (SmartCollection) -> Unit,
    onFolderSelected: (android.net.Uri) -> Unit,
    onRescan: () -> Unit,
    onDismissScanMessage: () -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnline: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    var sortMenuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.sort, uiState.query, uiState.collection) {
        if (uiState.titles.isNotEmpty()) gridState.scrollToItem(0)
    }
    // OpenDocumentTree grants access to the selected directory through SAF.
    // Asking for READ_MEDIA_VIDEO afterwards is redundant and, on modern Android,
    // makes a perfectly valid folder selection look like a permission failure.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(onFolderSelected) },
    )

    LaunchedEffect(uiState.scan) {
        val message = when (val scan = uiState.scan) {
            is ScanUiState.Finished -> buildString {
                append("Найдено: ${scan.titlesFound} тайтлов, ${scan.videosFound} видео")
                if (scan.autoRecognizedTitles > 0) append("; распознано: ${scan.autoRecognizedTitles}")
                if (scan.warningCount > 0) append("; предупреждений: ${scan.warningCount}")
            }
            is ScanUiState.Error -> scan.message
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onDismissScanMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = {
                        AnimeBrandTitle("Медиатека")
                    },
                    actions = {
                        VaultTopBarAction(
                            icon = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = "Сортировка",
                            onClick = { sortMenuVisible = true },
                        )
                        VaultTopBarAction(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Пересканировать",
                            onClick = onRescan,
                        )
                        VaultTopBarAction(
                            icon = Icons.Outlined.Settings,
                            contentDescription = "Настройки",
                            onClick = onOpenSettings,
                        )
                        Spacer(Modifier.width(8.dp))
                    },
                )
                LibrarySectionTabs(
                    selected = LibrarySection.Offline,
                    onSelect = { section ->
                        if (section == LibrarySection.Online) onOpenOnline()
                    },
                )
                VaultSearchField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    placeholder = "Найти в медиатеке",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                SmartCollectionRow(
                    selected = uiState.collection,
                    onSelected = onCollectionChange,
                )
                if (uiState.scan is ScanUiState.Scanning) {
                    val scan = uiState.scan
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "${scan.folderName}: просмотрено ${scan.visitedDocuments}, видео ${scan.videosFound}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { folderPicker.launch(null) },
                icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                text = { Text("Добавить папку", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                    defaultElevation = 5.dp,
                    pressedElevation = 2.dp,
                ),
            )
        },
    ) { innerPadding ->
        if (uiState.titles.isEmpty()) {
            LibraryEmptyState(
                hasQuery = uiState.query.isNotBlank(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(uiState.titles, key = LibraryTitleRow::id) { title ->
                    TitleCard(title = title, onClick = { onOpenTitle(title.id) })
                }
            }
        }
    }

    SortMenu(
        expanded = sortMenuVisible,
        selected = uiState.sort,
        onDismiss = { sortMenuVisible = false },
        onSelect = {
            onSortChange(it)
            sortMenuVisible = false
        },
    )
}

@Composable
private fun SmartCollectionRow(
    selected: SmartCollection,
    onSelected: (SmartCollection) -> Unit,
) {
    val labels = mapOf(
        SmartCollection.All to "Все",
        SmartCollection.InProgress to "В процессе",
        SmartCollection.Unwatched to "Не начато",
        SmartCollection.Completed to "Завершено",
        SmartCollection.LinkedOnline to "Связано онлайн",
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = SmartCollection.entries,
            key = SmartCollection::name,
        ) { collection ->
            FilterChip(
                selected = collection == selected,
                onClick = { onSelected(collection) },
                label = { Text(labels.getValue(collection)) },
            )
        }
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    selected: LibrarySort,
    onDismiss: () -> Unit,
    onSelect: (LibrarySort) -> Unit,
) {
    if (!expanded) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
        ) {
            VaultSheetHeader(
                title = "Сортировка медиатеки",
                subtitle = "Порядок меняется мгновенно и не затрагивает структуру файлов.",
                modifier = Modifier.padding(bottom = 12.dp),
            )
            listOf(
                LibrarySort.Alphabetical to "По алфавиту",
                LibrarySort.DateAdded to "По дате добавления",
                LibrarySort.LastWatched to "По последнему просмотру",
            ).forEach { (sort, label) ->
                val isSelected = sort == selected
                Surface(
                    onClick = { onSelect(sort) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 7.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                        if (isSelected) {
                            Text(
                                text = "Выбрано",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleCard(
    title: LibraryTitleRow,
    onClick: () -> Unit,
) {
    val progress = if (title.episodeCount > 0) {
        title.completedCount.toFloat() / title.episodeCount.toFloat()
    } else 0f
    val accent = remember(title.posterUri, title.name) {
        vaultAccentFor(title.posterUri ?: title.name)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .vaultClickable(onClick = onClick)
            .semantics { contentDescription = "${title.name}, серий ${title.episodeCount}" },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.26f),
        ),
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(274.dp),
        ) {
            if (title.posterUri != null) {
                AsyncImage(
                    model = title.posterUri,
                    contentDescription = "Обложка ${title.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = title.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.02f),
                            0.48f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.52f),
                            1.0f to Color.Black.copy(alpha = 0.94f),
                        ),
                    ),
            )

            if (title.onlineLinkCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp),
                    color = Color.Black.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        accent.copy(alpha = 0.38f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${title.onlineLinkCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color.Transparent,
                            0.18f to accent.copy(alpha = 0.34f),
                            0.50f to accent.copy(alpha = 0.88f),
                            0.82f to accent.copy(alpha = 0.34f),
                            1.0f to Color.Transparent,
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Text(
                    text = title.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                WatchProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    accent = accent,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${title.completedCount}/${title.episodeCount} просмотрено",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.76f),
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    hasQuery: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        VaultEmptyState(
            icon = if (hasQuery) Icons.Outlined.Search else Icons.Outlined.FolderOpen,
            title = if (hasQuery) "Ничего не найдено" else "Видеотека пока пуста",
            body = if (hasQuery) {
                "Измените запрос или очистите поиск, чтобы снова увидеть медиатеку."
            } else {
                "Добавьте папку с видео. AnimeVault найдёт серии и аккуратно соберёт их по тайтлам и сезонам."
            },
        )
    }
}
