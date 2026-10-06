package com.sergey.animevault.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.metadata.TenraiCatalogItem
import com.sergey.animevault.data.metadata.TenraiMetadataRepository
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.metadata.tenraiScheduleFilter
import com.sergey.animevault.ui.components.VaultArtworkSource
import com.sergey.animevault.ui.components.vaultTitleArtwork
import com.sergey.animevault.util.runCatchingCancellable
import java.util.Calendar

data class LibraryInsights(
    val watchedTimeMs: Long = 0L,
    val completionPercent: Int = 0,
    val totalBytes: Long = 0L,
    val reclaimableBytes: Long = 0L,
    val onlineHistoryCount: Int = 0,
)

internal fun buildLibraryInsights(
    titles: List<LibraryTitleRow>,
    onlineEntries: Collection<OnlineLibraryEntry>,
): LibraryInsights {
    val episodes = titles.sumOf { it.episodeCount.coerceAtLeast(0L) }
    val completed = titles.sumOf { it.completedCount.coerceAtLeast(0L) }
    return LibraryInsights(
        watchedTimeMs = titles.sumOf { it.watchedTimeMs.coerceAtLeast(0L) },
        completionPercent = if (episodes > 0L) ((completed * 100L) / episodes).toInt().coerceIn(0, 100) else 0,
        totalBytes = titles.sumOf { it.totalBytes.coerceAtLeast(0L) },
        reclaimableBytes = titles.sumOf { it.completedBytes.coerceAtLeast(0L) },
        onlineHistoryCount = onlineEntries.count(OnlineLibraryEntry::hasHistory),
    )
}

data class HomeUiState(
    val continueWatching: List<HomeContinueItem> = emptyList(),
    val recentlyAdded: List<LibraryTitleRow> = emptyList(),
    val onlineFavorites: List<OnlineLibraryEntry> = emptyList(),
    val onlineFavoriteKeys: Set<String> = emptySet(),
    val localTitleCount: Int = 0,
    val localEpisodeCount: Long = 0L,
    val completedEpisodeCount: Long = 0L,
    val insights: LibraryInsights = LibraryInsights(),
    val discovery: HomeDiscoveryState = HomeDiscoveryState(),
)

data class HomeDiscoveryState(
    val releases: List<OnlineReleaseCard> = emptyList(),
    val today: List<TenraiCatalogItem> = emptyList(),
    val season: List<TenraiCatalogItem> = emptyList(),
    val upcoming: List<TenraiCatalogItem> = emptyList(),
    val featuredIndex: Int = 0,
    val featuredDetails: OnlineReleaseDetails? = null,
    val featuredArtwork: List<VaultArtworkSource> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
) {
    val featured: OnlineReleaseCard? get() = releases.getOrNull(featuredIndex)
}

class HomeViewModel(
    repository: LibraryRepository,
    private val onlineRepository: OnlineRepository,
    private val tenraiMetadataRepository: TenraiMetadataRepository? = null,
    private val aniListMetadataRepository: AniListMetadataRepository? = null,
) : ViewModel() {
    private val discovery = MutableStateFlow(HomeDiscoveryState())
    private var discoveryJob: Job? = null
    private var featuredJob: Job? = null

    init { refreshDiscovery() }

    fun refreshDiscovery() {
        discoveryJob?.cancel()
        featuredJob?.cancel()
        discoveryJob = viewModelScope.launch {
            discovery.update { it.copy(loading = true, message = null) }
            supervisorScope {
                val catalog = async {
                    runCatchingCancellable { onlineRepository.getCatalog(OnlineProviderIds.UNIFIED, page = 1, limit = 24).releases }
                }
                val today = async { runCatchingCancellable {
                    tenraiMetadataRepository?.getSchedule(tenraiScheduleFilter(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)))?.items.orEmpty()
                }.getOrDefault(emptyList()) }
                val season = async { runCatchingCancellable { tenraiMetadataRepository?.getCurrentSeason()?.items.orEmpty() }.getOrDefault(emptyList()) }
                val upcoming = async { runCatchingCancellable { tenraiMetadataRepository?.getUpcomingSeason()?.items.orEmpty() }.getOrDefault(emptyList()) }
                val result = catalog.await()
                discovery.value = HomeDiscoveryState(
                    releases = result.getOrDefault(emptyList()).distinctBy { "${it.providerId}|${it.id}" },
                    loading = false,
                    message = result.exceptionOrNull()?.let { "Подборки временно недоступны" },
                )
                loadFeatured()
                val todayItems = today.await().distinctBy(TenraiCatalogItem::malId).take(12)
                val seasonItems = season.await().distinctBy(TenraiCatalogItem::malId).take(12)
                val upcomingItems = upcoming.await().distinctBy(TenraiCatalogItem::malId).take(12)
                discovery.update { it.copy(today = todayItems, season = seasonItems, upcoming = upcomingItems) }
            }
        }
    }

    fun nextFeatured() {
        val state = discovery.value
        if (state.releases.size < 2) return
        discovery.update { it.copy(featuredIndex = (state.featuredIndex + 1) % minOf(state.releases.size, 5),
            featuredDetails = null, featuredArtwork = emptyList()) }
        loadFeatured()
    }

    fun toggleFeaturedFavorite() {
        val release = discovery.value.featuredDetails ?: return
        viewModelScope.launch {
            onlineRepository.setFavorite(release, onlineRepository.libraryEntry(release.providerId, release.id)?.isFavorite != true)
        }
    }

    private fun loadFeatured() {
        featuredJob?.cancel()
        val card = discovery.value.featured ?: return
        featuredJob = viewModelScope.launch {
            val details = runCatchingCancellable { onlineRepository.getRelease(card.providerId, card.id) }.getOrNull()
            discovery.update { it.copy(featuredDetails = details, featuredArtwork = vaultTitleArtwork(providerPoster = details?.posterUrl ?: card.posterUrl)) }
            val malId = details?.externalIds?.malId ?: card.externalIds.malId
            val metadata = malId?.let { runCatchingCancellable { aniListMetadataRepository?.findAnimeByMalId(it) }.getOrNull() }
            discovery.update { it.copy(featuredArtwork = vaultTitleArtwork(metadata?.bannerUrl, metadata?.posterUrl, details?.posterUrl ?: card.posterUrl)) }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeHomeContinueWatching(),
        repository.observeLibrary(),
        onlineRepository.libraryEntries,
    ) { localContinue, localTitles, onlineEntries ->
        val onlineValues = onlineEntries.values
        val continueItems = buildList<HomeContinueItem> {
            localContinue.forEach { row ->
                add(
                    HomeContinueItem.Local(
                        episodeId = row.episodeId,
                        titleId = row.titleId,
                        title = row.titleName,
                        posterUri = row.posterUri,
                        episodeNumber = row.episodeNumber,
                        seasonNumber = row.seasonNumber,
                        positionMs = row.positionMs,
                        durationMs = row.durationMs,
                        lastWatchedAt = row.lastWatchedAt,
                    ),
                )
            }
            onlineValues
                .asSequence()
                .filter(OnlineLibraryEntry::hasContinueProgress)
                .forEach { entry ->
                    val episodeId = entry.lastEpisodeId ?: return@forEach
                    add(
                        HomeContinueItem.Online(
                            providerId = entry.providerId,
                            releaseId = entry.releaseId,
                            episodeId = episodeId,
                            title = entry.name,
                            posterUri = entry.posterUrl,
                            episodeOrdinal = entry.lastEpisodeOrdinal,
                            providerName = entry.providerName,
                            positionMs = entry.lastPositionMs,
                            durationMs = entry.lastDurationMs,
                            lastWatchedAt = entry.lastWatchedAt,
                        ),
                    )
                }
        }

        HomeUiState(
            continueWatching = rankContinueItems(continueItems),
            recentlyAdded = localTitles
                .sortedWith(compareByDescending<LibraryTitleRow> { it.dateAdded }.thenBy { it.name })
                .take(HOME_RECENT_LIMIT),
            onlineFavorites = onlineValues
                .asSequence()
                .filter(OnlineLibraryEntry::isFavorite)
                .sortedWith(
                    compareByDescending<OnlineLibraryEntry> { it.favoriteAddedAt }
                        .thenBy { it.name },
                )
                .take(HOME_FAVORITES_LIMIT)
                .toList(),
            onlineFavoriteKeys = onlineValues.filter(OnlineLibraryEntry::isFavorite).mapTo(mutableSetOf()) { "${it.providerId}|${it.releaseId}" },
            localTitleCount = localTitles.size,
            localEpisodeCount = localTitles.sumOf(LibraryTitleRow::episodeCount),
            completedEpisodeCount = localTitles.sumOf(LibraryTitleRow::completedCount),
            insights = buildLibraryInsights(localTitles, onlineValues),
        )
    }.combine(discovery) { state, feed -> state.copy(discovery = feed) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
        private val tenraiMetadataRepository: TenraiMetadataRepository? = null,
        private val aniListMetadataRepository: AniListMetadataRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(repository, onlineRepository, tenraiMetadataRepository, aniListMetadataRepository) as T
        }
    }
}
