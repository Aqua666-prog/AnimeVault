package com.sergey.animevault.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn


data class OnlineLibraryUiState(
    val favorites: List<OnlineLibraryEntry> = emptyList(),
    val continueWatching: List<OnlineLibraryEntry> = emptyList(),
    val history: List<OnlineLibraryEntry> = emptyList(),
)

class OnlineLibraryViewModel(
    private val repository: OnlineRepository,
) : ViewModel() {
    val uiState: StateFlow<OnlineLibraryUiState> = repository.libraryEntries
        .map { map ->
            val entries = map.values.toList()
            OnlineLibraryUiState(
                favorites = entries
                    .filter(OnlineLibraryEntry::isFavorite)
                    .sortedByDescending { it.favoriteAddedAt },
                continueWatching = entries
                    .filter(OnlineLibraryEntry::hasContinueProgress)
                    .sortedByDescending { it.lastWatchedAt },
                history = entries
                    .filter(OnlineLibraryEntry::hasHistory)
                    .sortedByDescending { maxOf(it.lastWatchedAt, it.lastOpenedAt) },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OnlineLibraryUiState(),
        )

    fun clearHistory() = repository.clearOnlineHistory()

    fun clearFavorites() = repository.clearOnlineFavorites()

    class Factory(
        private val repository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnlineLibraryViewModel(repository) as T
    }
}
