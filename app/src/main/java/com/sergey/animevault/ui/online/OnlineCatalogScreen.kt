package com.sergey.animevault.ui.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.ProviderAuthMode
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.ui.components.LibrarySection
import com.sergey.animevault.ui.components.LibrarySectionTabs
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultSearchField
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultSkeletonBlock
import com.sergey.animevault.ui.components.vaultClickable
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.theme.vaultAccentFor
import com.sergey.animevault.util.formatEpisodeNumber

@Composable
fun OnlineCatalogRoute(
    viewModel: OnlineCatalogViewModel,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTitle: (OnlineReleaseCard) -> Unit,
    onPlayEpisode: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnlineCatalogScreen(
        uiState = uiState,
        onQueryChange = viewModel::setQuery,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onSelectProvider = viewModel::selectProvider,
        onSelectGenre = viewModel::selectGenre,
        onSelectCollection = viewModel::selectCollection,
        onSelectSort = viewModel::selectSort,
        onSelectYear = viewModel::selectYear,
        onSelectType = viewModel::selectType,
        onSelectStatus = viewModel::selectStatus,
        onSelectEpisodeFilter = viewModel::selectEpisodeFilter,
        onToggleLayout = viewModel::toggleLayout,
        onResetDiscovery = viewModel::resetDiscovery,
        onOpenOffline = onOpenOffline,
        onOpenSettings = onOpenSettings,
        onOpenLibrary = onOpenLibrary,
        onOpenTitle = onOpenTitle,
        onPlayEpisode = onPlayEpisode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCatalogScreen(
    uiState: OnlineCatalogUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectCollection: (ThematicCollection) -> Unit,
    onSelectSort: (CatalogSort) -> Unit,
    onSelectYear: (Int?) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectStatus: (CatalogStatusFilter) -> Unit,
    onSelectEpisodeFilter: (CatalogEpisodeFilter) -> Unit,
    onToggleLayout: () -> Unit,
    onResetDiscovery: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTitle: (OnlineReleaseCard) -> Unit,
    onPlayEpisode: (String, String, String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(
        uiState.selectedProviderId,
        uiState.query,
        uiState.selectedGenre,
        uiState.selectedCollection,
        uiState.sort,
        uiState.selectedYear,
        uiState.selectedType,
        uiState.statusFilter,
        uiState.episodeFilter,
        uiState.layout,
        uiState.isLoading,
    ) {
        if (!uiState.isLoading && uiState.visibleReleases.isNotEmpty()) {
            gridState.scrollToItem(0)
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
                        AnimeBrandTitle("Онлайн · ${uiState.selectedProviderName}")
                    },
                    actions = {
                        VaultTopBarAction(
                            icon = Icons.Outlined.VideoLibrary,
                            contentDescription = "Моя медиатека",
                            onClick = onOpenLibrary,
                        )
                        VaultTopBarAction(
                            icon = Icons.Outlined.Settings,
                            contentDescription = "Настройки",
                            onClick = onOpenSettings,
                        )
                        VaultTopBarAction(
                            icon = if (uiState.layout == CatalogLayout.GRID) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            contentDescription = if (uiState.layout == CatalogLayout.GRID) "Показать списком" else "Показать сеткой",
                            onClick = onToggleLayout,
                        )
                        VaultTopBarAction(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Обновить каталог",
                            onClick = onRefresh,
                        )
                        Spacer(Modifier.width(8.dp))
                    },
                )
                LibrarySectionTabs(
                    selected = LibrarySection.Online,
                    onSelect = { section ->
                        if (section == LibrarySection.Offline) onOpenOffline()
                    },
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.providers, key = { it.id }) { provider ->
                        FilterChip(
                            selected = provider.id == uiState.selectedProviderId,
                            onClick = { onSelectProvider(provider.id) },
                            label = {
                                Text(
                                    buildString {
                                        append(provider.name)
                                        if (provider.authMode == ProviderAuthMode.REQUIRED_TOKEN) append(" · ключ")
                                        else if (provider.isExperimental) append(" · beta")
                                    },
                                )
                            },
                        )
                    }
                }
                VaultSearchField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    placeholder = uiState.searchHint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
                DiscoveryControls(
                    genres = uiState.availableGenres,
                    years = uiState.availableYears,
                    types = uiState.availableTypes,
                    selectedGenre = uiState.selectedGenre,
                    selectedYear = uiState.selectedYear,
                    selectedType = uiState.selectedType,
                    status = uiState.statusFilter,
                    episodeFilter = uiState.episodeFilter,
                    sort = uiState.sort,
                    onSelectGenre = onSelectGenre,
                    onSelectYear = onSelectYear,
                    onSelectType = onSelectType,
                    onSelectStatus = onSelectStatus,
                    onSelectEpisodeFilter = onSelectEpisodeFilter,
                    onSelectSort = onSelectSort,
                )
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> OnlineCatalogLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            uiState.errorMessage != null && uiState.releases.isEmpty() -> OnlineCatalogMessage(
                message = uiState.errorMessage,
                onRetry = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            uiState.releases.isEmpty() -> OnlineCatalogMessage(
                message = when {
                    uiState.selectedProviderId == OnlineProviderIds.JUT_SU && uiState.query.isBlank() ->
                        "Для Jut.su введите ссылку или slug страницы аниме"
                    uiState.selectedProviderId == OnlineProviderIds.ANIME_ON && uiState.query.isBlank() ->
                        "Для AnimeON введите название аниме: текущий адаптер использует публичный API поиска"
                    uiState.selectedProviderId == OnlineProviderIds.SAMEBAND && uiState.query.isNotBlank() && uiState.query.trim().length < 4 ->
                        "SameBand начинает поиск с 4 символов"
                    uiState.selectedProviderId == OnlineProviderIds.ANIME_BEST && uiState.query.isBlank() ->
                        "Для AnimeBest введите название аниме: адаптер использует поиск по каталогу"
                    else -> "По вашему запросу ничего не найдено"
                },
                onRetry = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            uiState.visibleReleases.isEmpty() -> DiscoveryEmptyState(
                canLoadMore = uiState.canLoadMore,
                onLoadMore = onLoadMore,
                onReset = onResetDiscovery,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyVerticalGrid(
                columns = if (uiState.layout == CatalogLayout.GRID) {
                    GridCells.Adaptive(minSize = 132.dp)
                } else {
                    GridCells.Fixed(1)
                },
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (uiState.query.isBlank() && uiState.continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinueWatchingShelf(
                            entries = uiState.continueWatching,
                            onOpen = { entry -> onOpenTitle(entry.toReleaseCard()) },
                            onPlay = { entry ->
                                entry.lastEpisodeId?.let { episodeId ->
                                    onPlayEpisode(entry.providerId, entry.releaseId, episodeId)
                                }
                            },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CatalogLoadSummary(
                        loadedCount = uiState.releases.size,
                        visibleCount = uiState.visibleReleases.size,
                        currentPage = uiState.currentPage,
                        canLoadMore = uiState.canLoadMore,
                        isLoadingMore = uiState.isLoadingMore,
                        filtered = uiState.hasDiscoverySelection,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ThematicCollections(
                        options = uiState.collections,
                        selected = uiState.selectedCollection,
                        onSelect = onSelectCollection,
                    )
                }
                itemsIndexed(
                    items = uiState.visibleReleases,
                    key = { _, item -> item.id },
                ) { index, release ->
                    if (uiState.layout == CatalogLayout.GRID) {
                        OnlineReleaseGridCard(
                            release = release,
                            onClick = { onOpenTitle(release) },
                        )
                    } else {
                        OnlineReleaseListCard(
                            release = release,
                            onClick = { onOpenTitle(release) },
                        )
                    }
                    if (index == uiState.visibleReleases.lastIndex && uiState.canLoadMore) {
                        LaunchedEffect(uiState.releases.size, uiState.selectedGenre, uiState.selectedCollection) {
                            onLoadMore()
                        }
                    }
                }
                if (uiState.isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    }
                }
                uiState.errorMessage?.let { error ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            Button(onClick = onLoadMore, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Повторить")
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Источник данных, обложек и видео: ${uiState.selectedProviderName}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryControls(
    genres: List<String>,
    years: List<Int>,
    types: List<String>,
    selectedGenre: String?,
    selectedYear: Int?,
    selectedType: String?,
    status: CatalogStatusFilter,
    episodeFilter: CatalogEpisodeFilter,
    sort: CatalogSort,
    onSelectGenre: (String?) -> Unit,
    onSelectYear: (Int?) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectStatus: (CatalogStatusFilter) -> Unit,
    onSelectEpisodeFilter: (CatalogEpisodeFilter) -> Unit,
    onSelectSort: (CatalogSort) -> Unit,
) {
    var genreMenuVisible by remember { mutableStateOf(false) }
    var filterMenuVisible by remember { mutableStateOf(false) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    val advancedSelected = selectedYear != null || selectedType != null ||
        status != CatalogStatusFilter.ALL || episodeFilter != CatalogEpisodeFilter.ANY

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selectedGenre != null,
            onClick = { genreMenuVisible = true },
            enabled = genres.isNotEmpty(),
            modifier = Modifier.weight(1f),
            leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null, Modifier.size(18.dp)) },
            label = {
                Text(
                    text = selectedGenre ?: if (genres.isEmpty()) "Жанры" else "Жанр",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        FilterChip(
            selected = advancedSelected,
            onClick = { filterMenuVisible = true },
            modifier = Modifier.weight(1f),
            label = { Text(if (advancedSelected) "Фильтры · on" else "Фильтры", maxLines = 1) },
        )
        FilterChip(
            selected = sort != CatalogSort.SOURCE,
            onClick = { sortMenuVisible = true },
            modifier = Modifier.weight(1f),
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, Modifier.size(18.dp)) },
            label = { Text("Сортировка", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }

    if (genreMenuVisible) {
        ModalBottomSheet(
            onDismissRequest = { genreMenuVisible = false },
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
                    title = "Жанр",
                    subtitle = "Фильтр применяется к уже загруженной части каталога.",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item(key = "all-genres") {
                        DiscoverySheetOption("Все жанры", selectedGenre == null) {
                            onSelectGenre(null); genreMenuVisible = false
                        }
                    }
                    items(genres, key = { it }) { genre ->
                        DiscoverySheetOption(genre, selectedGenre == genre) {
                            onSelectGenre(genre); genreMenuVisible = false
                        }
                    }
                }
            }
        }
    }

    if (filterMenuVisible) {
        ModalBottomSheet(
            onDismissRequest = { filterMenuVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 24.dp)
                    .heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item {
                    VaultSheetHeader(
                        title = "Фильтры каталога",
                        subtitle = "Год, тип, статус и длина сериала. Фильтры работают вместе.",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                item { Text("Статус", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                items(CatalogStatusFilter.entries, key = { "status-${it.name}" }) { option ->
                    DiscoverySheetOption(option.title, status == option) { onSelectStatus(option) }
                }
                item { Text("Количество серий", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(CatalogEpisodeFilter.entries, key = { "episodes-${it.name}" }) { option ->
                    DiscoverySheetOption(option.title, episodeFilter == option) { onSelectEpisodeFilter(option) }
                }
                if (years.isNotEmpty()) {
                    item { Text("Год", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    item(key = "year-any") {
                        DiscoverySheetOption("Любой год", selectedYear == null) { onSelectYear(null) }
                    }
                    items(years, key = { "year-$it" }) { year ->
                        DiscoverySheetOption(year.toString(), selectedYear == year) { onSelectYear(year) }
                    }
                }
                if (types.isNotEmpty()) {
                    item { Text("Тип", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    item(key = "type-any") {
                        DiscoverySheetOption("Любой тип", selectedType == null) { onSelectType(null) }
                    }
                    items(types, key = { "type-$it" }) { type ->
                        DiscoverySheetOption(type, selectedType == type) { onSelectType(type) }
                    }
                }
            }
        }
    }

    if (sortMenuVisible) {
        ModalBottomSheet(
            onDismissRequest = { sortMenuVisible = false },
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
                    title = "Сортировка",
                    subtitle = "Источник можно оставить в исходном порядке или переупорядочить локально.",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    CatalogSort.entries.forEach { option ->
                        DiscoverySheetOption(option.title, option == sort) {
                            onSelectSort(option); sortMenuVisible = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySheetOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            if (selected) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = "Выбрано",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingShelf(
    entries: List<OnlineLibraryEntry>,
    onOpen: (OnlineLibraryEntry) -> Unit,
    onPlay: (OnlineLibraryEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Продолжить просмотр",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { "${it.providerId}|${it.releaseId}" }) { entry ->
                Surface(
                    modifier = Modifier
                        .width(238.dp)
                        .vaultClickable { onOpen(entry) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                    ),
                ) {
                    Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(width = 62.dp, height = 88.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            if (entry.posterUrl != null) {
                                AsyncImage(
                                    model = entry.posterUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Movie, contentDescription = null)
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = listOfNotNull(
                                    entry.lastEpisodeOrdinal?.let { "Серия ${formatEpisodeNumber(it)}" },
                                    entry.providerName,
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            WatchProgressBar(
                                progress = entry.progressFraction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            onClick = { onPlay(entry) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "Продолжить",
                                modifier = Modifier.padding(9.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogLoadSummary(
    loadedCount: Int,
    visibleCount: Int,
    currentPage: Int,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    filtered: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = if (filtered) {
                    "Показано: $visibleCount из $loadedCount загруженных"
                } else {
                    "Загружено: $loadedCount"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    if (currentPage > 0) append("Страница $currentPage")
                    if (currentPage > 0) append(" · ")
                    append(
                        when {
                            isLoadingMore -> "загружаю следующую"
                            canLoadMore -> "у источника есть ещё страницы"
                            else -> "следующих страниц источник не сообщил"
                        },
                    )
                },
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThematicCollections(
    options: List<CollectionOption>,
    selected: ThematicCollection,
    onSelect: (ThematicCollection) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Подборки",
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Числа ниже считаются только среди уже загруженных релизов",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options, key = { it.collection.name }) { option ->
                FilterChip(
                    selected = option.collection == selected,
                    onClick = { onSelect(option.collection) },
                    label = {
                        Text(
                            text = "${option.collection.title} · ${option.count}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DiscoveryEmptyState(
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.FilterAlt,
                contentDescription = null,
                modifier = Modifier.size(58.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "В загруженной части каталога ничего не подошло",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Сбросьте фильтры или загрузите следующую страницу источника.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onReset) { Text("Сбросить") }
                if (canLoadMore) {
                    Button(onClick = onLoadMore) { Text("Ещё релизы") }
                }
            }
        }
    }
}

@Composable
private fun OnlineReleaseListCard(
    release: OnlineReleaseCard,
    onClick: () -> Unit,
) {
    val accent = remember(release.posterUrl, release.name) { vaultAccentFor(release.posterUrl ?: release.name) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .vaultClickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(width = 82.dp, height = 118.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (release.posterUrl != null) {
                    AsyncImage(
                        model = release.posterUrl,
                        contentDescription = "Обложка ${release.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Movie, contentDescription = null, tint = accent)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    release.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    listOfNotNull(release.year?.toString(), release.type, release.season).joinToString(" · ")
                        .ifBlank { "Онлайн-релиз" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                release.episodeCount?.let {
                    Text(
                        "$it серий${if (release.isOngoing) " · выходит" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (release.isOngoing) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (release.genres.isNotEmpty()) {
                    Text(
                        release.genres.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineReleaseGridCard(
    release: OnlineReleaseCard,
    onClick: () -> Unit,
) {
    val episodeText = release.episodeCount?.let { "$it эп." }
    val accent = remember(release.posterUrl, release.name) {
        vaultAccentFor(release.posterUrl ?: release.name)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .vaultClickable(onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append(release.name)
                    episodeText?.let { append(", $it") }
                    if (release.isOngoing) append(", выходит")
                }
            },
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
                .aspectRatio(0.67f),
        ) {
            if (release.posterUrl != null) {
                AsyncImage(
                    model = release.posterUrl,
                    contentDescription = "Обложка ${release.name}",
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
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.02f),
                            0.46f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.50f),
                            1.0f to Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
            )

            if (release.isOngoing) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    color = Color.Black.copy(alpha = 0.64f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        accent.copy(alpha = 0.42f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(accent),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "ВЫХОДИТ",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            episodeText?.let { count ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color.Black.copy(alpha = 0.64f),
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.10f),
                    ),
                ) {
                    Text(
                        text = count,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
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
                    .padding(11.dp),
            ) {
                Text(
                    text = release.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = listOfNotNull(
                        release.year?.toString(),
                        release.type,
                        release.season,
                    ).joinToString(" · ").ifBlank { "Онлайн-релиз" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
                if (release.genres.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = release.genres.take(2).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.copy(alpha = 0.96f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineCatalogMessage(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        VaultEmptyState(
            icon = Icons.Outlined.CloudOff,
            title = if (onRetry != null) "Источник недоступен" else "Здесь пока пусто",
            body = message,
            actionLabel = if (onRetry != null) "Повторить" else null,
            onAction = onRetry,
        )
    }
}

@Composable
private fun OnlineCatalogLoading(
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(8) { index ->
            Column {
                VaultSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.67f),
                    shape = RoundedCornerShape(22.dp),
                )
                Spacer(Modifier.height(8.dp))
                VaultSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 3 == 0) 0.74f else 0.9f)
                        .height(11.dp),
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }
}
