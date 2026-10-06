package com.sergey.animevault.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.ui.home.HomeContinueItem
import com.sergey.animevault.ui.home.rankContinueItems
import com.sergey.animevault.ui.preferences.UiPreferences
import com.sergey.animevault.ui.preferences.VaultTitleList
import com.sergey.animevault.ui.preferences.vaultLocalListKey
import com.sergey.animevault.ui.preferences.vaultOnlineListKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A presentation join; media, favourite and progress stores keep their ownership. */
data class LibraryHubItem(
    val key: String,
    val title: String,
    val poster: String?,
    val metadata: String,
    val favorite: Boolean,
    val list: VaultTitleList,
    val localId: Long? = null,
    val providerId: String? = null,
    val releaseId: String? = null,
)

data class LibraryHubUiState(
    val items: List<LibraryHubItem> = emptyList(),
    val continueWatching: List<HomeContinueItem> = emptyList(),
    val loading: Boolean = true,
)

internal fun libraryHubItems(
    local: List<LibraryTitleRow>, online: Collection<OnlineLibraryEntry>,
    lists: Map<String, VaultTitleList>, localFavorites: Set<Long>,
): List<LibraryHubItem> = buildList {
    local.forEach { title ->
        val key = vaultLocalListKey(title.id)
        val complete = title.episodeCount > 0 && title.completedCount >= title.episodeCount
        add(LibraryHubItem(key, title.name, title.posterUri, "${title.episodeCount} серий · на устройстве",
            title.id in localFavorites, lists[key] ?: if (complete) VaultTitleList.WATCHED else VaultTitleList.NONE, localId = title.id))
    }
    online.forEach { entry ->
        val key = vaultOnlineListKey(entry.providerId, entry.releaseId)
        add(LibraryHubItem(key, entry.name, entry.posterUrl,
            listOfNotNull(entry.year?.toString(), entry.type).joinToString(" · "), entry.isFavorite,
            lists[key] ?: VaultTitleList.NONE, providerId = entry.providerId, releaseId = entry.releaseId))
    }
}.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

class LibraryHubViewModel(
    localRepository: LibraryRepository,
    onlineRepository: OnlineRepository,
    private val preferences: UiPreferences,
) : ViewModel() {
    val uiState: StateFlow<LibraryHubUiState> = combine(
        localRepository.observeLibrary(), onlineRepository.libraryEntries, preferences.titleLists, preferences.localFavorites,
    ) { local, online, lists, favorites ->
        LibraryHubUiState(items = libraryHubItems(local, online.values, lists, favorites),
            continueWatching = online.values.filter(OnlineLibraryEntry::hasContinueProgress).mapNotNull { entry ->
                entry.lastEpisodeId?.let { episode ->
                    HomeContinueItem.Online(entry.providerId, entry.releaseId, episode, entry.name, entry.posterUrl,
                        entry.lastEpisodeOrdinal, entry.providerName, entry.lastPositionMs, entry.lastDurationMs, entry.lastWatchedAt)
                }
            }, loading = false)
    }.combine(localRepository.observeHomeContinueWatching()) { state, local ->
        val localItems = local.map { row -> HomeContinueItem.Local(row.episodeId, row.titleId, row.titleName, row.posterUri,
            row.episodeNumber, row.seasonNumber, row.positionMs, row.durationMs, row.lastWatchedAt) }
        state.copy(continueWatching = rankContinueItems(state.continueWatching + localItems, limit = Int.MAX_VALUE))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryHubUiState())

    fun setList(key: String, list: VaultTitleList) = preferences.setTitleList(key, list)

    class Factory(private val local: LibraryRepository, private val online: OnlineRepository, private val preferences: UiPreferences) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryHubViewModel::class.java))
            return LibraryHubViewModel(local, online, preferences) as T
        }
    }
}
