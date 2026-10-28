package com.sergey.animevault.ui.home

import com.sergey.animevault.ui.title.animeTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import com.sergey.animevault.ui.theme.vaultMotionDuration
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.preferences.VaultMotionMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.sergey.animevault.ui.components.VaultAdaptiveHero
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.data.metadata.TenraiCatalogItem
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultArtwork
import com.sergey.animevault.ui.components.VaultPosterCard
import com.sergey.animevault.ui.components.VaultPrimaryButton
import com.sergey.animevault.ui.components.VaultSectionHeader
import com.sergey.animevault.ui.components.VaultSkeletonBlock
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.vaultClickable
import com.sergey.animevault.ui.components.vaultTitleArtwork
import com.sergey.animevault.ui.navigation.VaultSharedPosterKey
import com.sergey.animevault.ui.theme.LocalVaultColors
import com.sergey.animevault.util.formatEpisodeNumber

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
    onDiscoverTitle: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state, onOpenOffline, onOpenOnline, onOpenClips, onOpenSettings, onOpenStatistics,
        onOpenLocalTitle, onPlayLocalEpisode, onOpenOnlineTitle, onPlayOnlineEpisode, onDiscoverTitle,
        viewModel::refreshDiscovery, viewModel::nextFeatured, viewModel::toggleFeaturedFavorite)
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenOffline: () -> Unit,
    onOpenOnline: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenLocalTitle: (Long) -> Unit,
    onPlayLocalEpisode: (Long) -> Unit,
    onOpenOnlineTitle: (String, String) -> Unit,
    onPlayOnlineEpisode: (String, String, String) -> Unit,
    onDiscoverTitle: (String) -> Unit,
    onRefresh: () -> Unit,
    onNextFeatured: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val feed = uiState.discovery
    val playContinue: (HomeContinueItem) -> Unit = { item ->
        when (item) {
            is HomeContinueItem.Local -> onPlayLocalEpisode(item.episodeId)
            is HomeContinueItem.Online -> onPlayOnlineEpisode(item.providerId, item.releaseId, item.episodeId)
        }
    }
    val motion = LocalVaultVisualSettings.current.motion
    val preview = LocalInspectionMode.current
    val entrance = remember { Animatable(if (motion == VaultMotionMode.OFF || preview) 1f else 0f) }
    val duration = vaultMotionDuration(VaultMotion.standard)
    LaunchedEffect(motion) {
        if (motion == VaultMotionMode.OFF || preview) entrance.snapTo(1f)
        else {
            entrance.animateTo(1f, tween(duration))
        }
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyVerticalGrid(columns = GridCells.Adaptive(144.dp * fontScale),
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = entrance.value }, contentPadding = PaddingValues(bottom = VaultSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            HomeHero(uiState, onOpenOnline, onOpenOnlineTitle, onPlayOnlineEpisode, onNextFeatured,
                onToggleFavorite, onOpenOffline, onOpenClips, onOpenSettings, onOpenStatistics)
        }
        if (uiState.continueWatching.isNotEmpty()) {
            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    VaultSectionHeader("Продолжить просмотр")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(uiState.continueWatching, key = HomeContinueItem::stableKey) { item ->
                            ContinueWatchingCard(item, { playContinue(item) })
                        }
                    }
                }
            }
        }
        if (feed.today.isNotEmpty()) item(key = "today", span = { GridItemSpan(maxLineSpan) }) {
            HomeDiscoveryShelf("Новые серии", feed.today, "Сегодня по расписанию", onDiscoverTitle)
        }
        val ongoing = feed.releases.filter { it.isOngoing }
        if (ongoing.isNotEmpty()) item(key = "ongoing", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                VaultSectionHeader("Сейчас выходит")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ongoing, key = { "${it.providerId}|${it.id}" }) { card ->
                        VaultPosterCard(animeTitle(card), card.posterUrl, { onOpenOnlineTitle(card.providerId, card.id) },
                            Modifier.width(136.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)), metadata = listOfNotNull(card.year?.toString(), card.type).joinToString(" · "),
                            sharedKey = VaultSharedPosterKey("online:${card.providerId}", card.id))
                    }
                }
            }
        }
        if (feed.releases.isNotEmpty()) {
            item(key = "discovery-heading", span = { GridItemSpan(maxLineSpan) }) {
                VaultSectionHeader("Откройте для себя", "Истории для вашего следующего вечера")
            }
            items(feed.releases.take(12), key = { "discovery:${it.providerId}|${it.id}" }) { card ->
                val continuing = uiState.continueWatching.any {
                    it is HomeContinueItem.Online && it.providerId == card.providerId && it.releaseId == card.id
                }
                VaultPosterCard(animeTitle(card), card.posterUrl,
                    { onOpenOnlineTitle(card.providerId, card.id) },
                    Modifier.padding(horizontal = VaultSpacing.sm),
                    metadata = listOfNotNull(card.year?.toString(), card.type).joinToString(" · "),
                    badge = when {
                        continuing -> "Смотрите"
                        "${card.providerId}|${card.id}" in uiState.onlineFavoriteKeys -> "В библиотеке"
                        card.isOngoing -> "Выходит"
                        else -> null
                    },
                    // Ongoing releases already own a shared element in the shelf above.
                    sharedKey = if (card.isOngoing) null else VaultSharedPosterKey("online:${card.providerId}", card.id))
            }
        } else if (feed.loading) {
            item(key = "catalog-loading", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(VaultSpacing.lg), verticalArrangement = Arrangement.spacedBy(VaultSpacing.md)) {
                    Text("Загружаем подборки", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md)) {
                        repeat(2) { VaultSkeletonBlock(Modifier.weight(1f).aspectRatio(2f / 3f)) }
                    }
                }
            }
        } else if (feed.message == null) {
            item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                VaultEmptyState(Icons.Outlined.Search, "Найдите свою историю",
                    "В подборке пока нет тайтлов. Откройте каталог и выберите аниме по настроению.",
                    actionLabel = "Открыть каталог", onAction = onOpenOnline)
            }
        }
        if (feed.season.isNotEmpty()) item(key = "season", span = { GridItemSpan(maxLineSpan) }) {
            HomeDiscoveryShelf("Текущий сезон", feed.season, null, onDiscoverTitle)
        }
        if (feed.upcoming.isNotEmpty()) item(key = "upcoming", span = { GridItemSpan(maxLineSpan) }) {
            HomeDiscoveryShelf("Скоро", feed.upcoming, null, onDiscoverTitle)
        }
        if (uiState.recentlyAdded.isNotEmpty()) item(key = "local", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                VaultSectionHeader("На вашем устройстве")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.recentlyAdded, key = { it.id }) { title ->
                        VaultPosterCard(animeTitle(title), title.posterUri, { onOpenLocalTitle(title.id) }, Modifier.width(136.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                            metadata = "${title.episodeCount} серий", sharedKey = VaultSharedPosterKey("local", title.id.toString()))
                    }
                }
            }
        }
        if (feed.message != null) item(key = "retry", span = { GridItemSpan(maxLineSpan) }) {
            VaultEmptyState(Icons.Outlined.Search, feed.message,
                "Попробуйте ещё раз. Сохранённые тайтлы и продолжение просмотра остаются доступны.",
                actionLabel = "Повторить", onAction = onRefresh)
        }
    }
}

@Composable
private fun HomeHero(
    uiState: HomeUiState,
    onOpenCatalog: () -> Unit,
    onOpenTitle: (String, String) -> Unit,
    onPlay: (String, String, String) -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
) {
    val feed = uiState.discovery
    val featured = feed.featured
    val details = feed.featuredDetails
    // Only use details belonging to the visible release while asynchronous metadata updates arrive.
    val visibleDetails = details?.takeIf { it.providerId == featured?.providerId && it.id == featured?.id }
    val resume = uiState.continueWatching.filterIsInstance<HomeContinueItem.Online>()
        .firstOrNull { it.providerId == featured?.providerId && it.releaseId == featured?.id }
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(VaultSpacing.lg),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AnimeBrandTitle("Что посмотрим?") }
            VaultTopBarAction(Icons.Outlined.Search, "Найти аниме", onOpenCatalog)
            Box {
                VaultTopBarAction(Icons.Outlined.MoreVert, "Ещё", { showMenu = true })
                DropdownMenu(showMenu, { showMenu = false }) {
                    DropdownMenuItem(text = { Text("На устройстве") }, onClick = { showMenu = false; onOpenOffline() })
                    DropdownMenuItem(text = { Text("Клипы") }, onClick = { showMenu = false; onOpenClips() })
                    DropdownMenuItem(text = { Text("Статистика") }, onClick = { showMenu = false; onOpenStatistics() })
                    DropdownMenuItem(text = { Text("Настройки") }, onClick = { showMenu = false; onOpenSettings() })
                }
            }
        }
        VaultAdaptiveHero(
            poster = featured?.posterUrl, seed = featured?.id.orEmpty(),
            title = featured?.let { animeTitle(it) } ?: if (feed.loading) "Выбираем историю для вас" else "Ваша следующая история",
            posterContentDescription = "", // The adjacent heading already names the title.
            backdropSources = feed.featuredArtwork.ifEmpty { vaultTitleArtwork(providerPoster = featured?.posterUrl) },
            details = {
                Text(if (resume != null) "ПРОДОЛЖИТЬ ИСТОРИЮ" else "ВЫБОР ВЕЧЕРА",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                val meta = listOfNotNull(featured?.year?.toString(), featured?.type,
                    featured?.episodeCount?.let { "$it серий" })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(visibleDetails?.genres?.take(3)?.joinToString(" · ").orEmpty(),
                    style = MaterialTheme.typography.labelMedium, minLines = 1, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            actions = {
                Column(verticalArrangement = Arrangement.spacedBy(VaultSpacing.md)) {
                    // Reserve three scalable lines while details arrive, keeping the CTA stable.
                    val descriptionHeight = with(LocalDensity.current) {
                        MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 3
                    }
                    Text(visibleDetails?.description?.takeIf { it.isNotBlank() }
                        ?: "Выберите историю по настроению — и погрузитесь в её мир.",
                        modifier = Modifier.heightIn(min = descriptionHeight),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    resume?.let {
                        Text(continueSubtitle(it), style = MaterialTheme.typography.labelMedium)
                        WatchProgressBar(it.progressFraction, Modifier.fillMaxWidth().height(VaultSize.progress))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(VaultSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        val episode = visibleDetails?.episodes?.firstOrNull { it.hasStream }
                        VaultPrimaryButton(
                            text = when { resume != null -> "Продолжить"; featured == null -> "Найти аниме"
                                episode != null -> "Смотреть"; else -> "Открыть тайтл" },
                            onClick = {
                                when {
                                    resume != null -> onPlay(resume.providerId, resume.releaseId, resume.episodeId)
                                    featured == null -> onOpenCatalog()
                                    episode != null -> onPlay(featured.providerId, featured.id, episode.id)
                                    else -> onOpenTitle(featured.providerId, featured.id)
                                }
                            }, modifier = Modifier.weight(1f), icon = Icons.Outlined.PlayArrow)
                        if (visibleDetails != null) {
                            val favorite = "${visibleDetails.providerId}|${visibleDetails.id}" in uiState.onlineFavoriteKeys
                            VaultTopBarAction(if (favorite) Icons.Outlined.Check else Icons.Outlined.Add,
                                if (favorite) "Убрать из библиотеки" else "Добавить в библиотеку", onFavorite)
                        }
                        if (feed.releases.size > 1) VaultTopBarAction(Icons.Outlined.ChevronRight, "Следующий тайтл", onNext)
                    }
                }
            })
    }
}

@Composable
private fun HomeDiscoveryShelf(title: String, items: List<TenraiCatalogItem>, subtitle: String?, onSelect: (String) -> Unit) {
    Column {
        VaultSectionHeader(title, subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.malId }) { item ->
                VaultPosterCard(animeTitle(item), item.imageUrl, { onSelect(item.title) }, Modifier.width(136.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    metadata = listOfNotNull(item.year?.toString(), item.type).joinToString(" · "))
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(item: HomeContinueItem, onClick: () -> Unit, modifier: Modifier = Modifier, fillAvailableWidth: Boolean = false) {
    val displayTitle = animeTitle(item)
    val cardWidth = 264.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    Column((if (fillAvailableWidth) modifier.fillMaxWidth() else modifier.width(cardWidth)).clip(RoundedCornerShape(VaultRadius.medium))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics(mergeDescendants = true) { contentDescription = "Продолжить $displayTitle, ${continueSubtitle(item)}" }
        .vaultClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            VaultArtwork(vaultTitleArtwork(providerPoster = item.posterUri), Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.Center), color = Color.Black.copy(alpha = .7f), shape = RoundedCornerShape(50)) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.padding(12.dp).size(24.dp),
                    tint = LocalVaultColors.current.action)
            }
            WatchProgressBar(item.progressFraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(VaultSize.progress))
        }
        Text(animeTitle(item), modifier = Modifier.padding(horizontal = VaultSpacing.md), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(continueSubtitle(item), modifier = Modifier.padding(start = VaultSpacing.md, end = VaultSpacing.md, bottom = VaultSpacing.md), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun continueSubtitle(item: HomeContinueItem): String {
    val episode = when (item) {
        is HomeContinueItem.Local -> item.episodeNumber
        is HomeContinueItem.Online -> item.episodeOrdinal
    }
    val position = when (item) { is HomeContinueItem.Local -> item.positionMs; is HomeContinueItem.Online -> item.positionMs }
    val duration = when (item) { is HomeContinueItem.Local -> item.durationMs; is HomeContinueItem.Online -> item.durationMs }
    return listOfNotNull(episode?.let { "Серия ${formatEpisodeNumber(it)}" },
        duration.takeIf { it > position && it > 0 }?.let { "осталось ${(it - position + 59_999) / 60_000} мин" })
        .joinToString(" · ").ifEmpty { "Продолжить просмотр" }
}
