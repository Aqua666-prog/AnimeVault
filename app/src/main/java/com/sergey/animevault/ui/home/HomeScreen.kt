package com.sergey.animevault.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
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
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.navigation.VaultSharedPosterKey
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultColors
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import com.sergey.animevault.ui.theme.vaultMotionDuration
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
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "hero") {
            HomeHero(uiState, onOpenOnline, onOpenOnlineTitle, onPlayOnlineEpisode, onNextFeatured,
                onToggleFavorite, onOpenOffline, onOpenClips, onOpenSettings, onOpenStatistics)
        }
        if (uiState.continueWatching.isNotEmpty()) {
            item(key = "continue") {
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
        if (feed.today.isNotEmpty()) item(key = "today") {
            HomeDiscoveryShelf("Новые серии", feed.today, "Сегодня по расписанию", onDiscoverTitle)
        }
        val ongoing = feed.releases.filter { it.isOngoing }
        if (ongoing.isNotEmpty()) item(key = "ongoing") {
            Column {
                VaultSectionHeader("Сейчас выходит")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ongoing, key = { "${it.providerId}|${it.id}" }) { card ->
                        VaultPosterCard(card.name, card.posterUrl, { onOpenOnlineTitle(card.providerId, card.id) },
                            Modifier.width(136.dp), metadata = listOfNotNull(card.year?.toString(), card.type).joinToString(" · "),
                            sharedKey = VaultSharedPosterKey("online:${card.providerId}", card.id))
                    }
                }
            }
        }
        if (feed.releases.isNotEmpty()) item(key = "discovery") {
            Column {
                VaultSectionHeader("Откройте для себя")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(feed.releases.take(12), key = { "${it.providerId}|${it.id}" }) { card ->
                        VaultPosterCard(card.name, card.posterUrl, { onOpenOnlineTitle(card.providerId, card.id) },
                            Modifier.width(136.dp), sharedKey = null)
                    }
                }
            }
        }
        if (feed.season.isNotEmpty()) item(key = "season") {
            HomeDiscoveryShelf("Текущий сезон", feed.season, null, onDiscoverTitle)
        }
        if (feed.upcoming.isNotEmpty()) item(key = "upcoming") {
            HomeDiscoveryShelf("Скоро", feed.upcoming, null, onDiscoverTitle)
        }
        if (uiState.recentlyAdded.isNotEmpty()) item(key = "local") {
            Column {
                VaultSectionHeader("На вашем устройстве")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.recentlyAdded, key = { it.id }) { title ->
                        VaultPosterCard(title.name, title.posterUri, { onOpenLocalTitle(title.id) }, Modifier.width(136.dp),
                            metadata = "${title.episodeCount} серий", sharedKey = VaultSharedPosterKey("local", title.id.toString()))
                    }
                }
            }
        }
        if (feed.message != null) item(key = "retry") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(feed.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh) { Text("Повторить") }
            }
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
    val backdropHeight = (LocalConfiguration.current.screenHeightDp * 0.44f).coerceIn(240f, 430f).dp
    val heroHeight = backdropHeight + 156.dp
    val heroDuration = vaultMotionDuration(VaultMotion.hero)
    val fullMotion = LocalVaultVisualSettings.current.motion == VaultMotionMode.FULL
    var showMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(heroHeight)) {
        Crossfade(targetState = feed.featuredArtwork.ifEmpty { vaultTitleArtwork(providerPoster = featured?.posterUrl) },
            animationSpec = tween(heroDuration), label = "home-backdrop") { sources ->
            VaultArtwork(sources, Modifier.fillMaxWidth().height(backdropHeight + 60.dp), backdrop = true)
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
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
        if (feed.loading && featured == null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultSkeletonBlock(Modifier.fillMaxWidth(.7f).height(26.dp))
                VaultSkeletonBlock(Modifier.fillMaxWidth(.5f).height(18.dp))
                VaultSkeletonBlock(Modifier.fillMaxWidth().height(48.dp))
            }
        } else {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (featured?.isOngoing == true) "СЕЙЧАС ВЫХОДИТ" else "ANIMEVAULT · ВЫБОР ВЕЧЕРА",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                AnimatedContent(targetState = featured?.name ?: "Ваша следующая история",
                    transitionSpec = { (fadeIn(tween(heroDuration)) + slideInVertically(tween(heroDuration)) { if (fullMotion) it / 5 else 0 })
                        .togetherWith(fadeOut(tween(heroDuration))) }, label = "home-title") { title ->
                    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(featured?.year?.toString(), featured?.type, featured?.episodeCount?.let { "$it серий" })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                details?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                    Text(genres.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val description = details?.description ?: if (featured == null) "Найдите аниме по настроению или продолжите любимую историю." else null
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val episode = details?.episodes?.firstOrNull { it.hasStream }
                    VaultPrimaryButton(
                        text = if (featured == null) "Найти аниме" else if (episode != null) "Смотреть" else "Открыть тайтл",
                        onClick = {
                            if (featured == null) onOpenCatalog()
                            else if (episode != null) onPlay(featured.providerId, featured.id, episode.id)
                            else onOpenTitle(featured.providerId, featured.id)
                        }, modifier = Modifier.weight(1f), icon = Icons.Outlined.PlayArrow)
                    if (details != null) {
                        val favorite = "${details.providerId}|${details.id}" in uiState.onlineFavoriteKeys
                        Surface(onClick = onFavorite, color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(14.dp)) {
                            Icon(if (favorite) Icons.Outlined.Check else Icons.Outlined.Add,
                                contentDescription = if (favorite) "Убрать из библиотеки" else "Добавить в библиотеку",
                                modifier = Modifier.padding(13.dp).size(22.dp))
                        }
                    }
                    if (feed.releases.size > 1) {
                        VaultTopBarAction(Icons.Outlined.ChevronRight, "Следующий тайтл", onNext)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDiscoveryShelf(title: String, items: List<TenraiCatalogItem>, subtitle: String?, onSelect: (String) -> Unit) {
    Column {
        VaultSectionHeader(title, subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.malId }) { item ->
                VaultPosterCard(item.title, item.imageUrl, { onSelect(item.title) }, Modifier.width(136.dp),
                    metadata = listOfNotNull(item.year?.toString(), item.type).joinToString(" · "))
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(item: HomeContinueItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(248.dp).vaultClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            VaultArtwork(vaultTitleArtwork(providerPoster = item.posterUri), Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.Center), color = Color.Black.copy(alpha = .7f), shape = RoundedCornerShape(50)) {
                Icon(Icons.Outlined.PlayArrow, "Продолжить ${item.title}", Modifier.padding(12.dp).size(24.dp),
                    tint = LocalVaultColors.current.action)
            }
            WatchProgressBar(item.progressFraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(continueSubtitle(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
