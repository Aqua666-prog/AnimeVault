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
    val localTitleCount: Int = 0,
    val localEpisodeCount: Long = 0L,
    val completedEpisodeCount: Long = 0L,
    val insights: LibraryInsights = LibraryInsights(),
)

class HomeViewModel(
    repository: LibraryRepository,
    onlineRepository: OnlineRepository,
) : ViewModel() {
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
            localTitleCount = localTitles.size,
            localEpisodeCount = localTitles.sumOf(LibraryTitleRow::episodeCount),
            completedEpisodeCount = localTitles.sumOf(LibraryTitleRow::completedCount),
            insights = buildLibraryInsights(localTitles, onlineValues),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            return HomeViewModel(repository, onlineRepository) as T
        }
    }
}
