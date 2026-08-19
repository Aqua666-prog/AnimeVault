package com.sergey.animevault.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.model.LocalHistoryRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class HistoryFilter {
    ALL,
    LOCAL,
    ONLINE,
}

sealed interface HistoryItem {
    val timestamp: Long
    val title: String
    val poster: String?

    data class Local(
        val row: LocalHistoryRow,
    ) : HistoryItem {
        override val timestamp: Long = row.lastWatchedAt
        override val title: String = row.titleName
        override val poster: String? = row.posterUri
    }

    data class Online(
        val entry: OnlineLibraryEntry,
    ) : HistoryItem {
        override val timestamp: Long = maxOf(entry.lastWatchedAt, entry.lastOpenedAt)
        override val title: String = entry.name
        override val poster: String? = entry.posterUrl
    }
}

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val localCount: Int = 0,
    val onlineCount: Int = 0,
)

internal fun buildHistoryUiState(
    local: List<LocalHistoryRow>,
    online: Collection<OnlineLibraryEntry>,
    filter: HistoryFilter,
): HistoryUiState {
    val onlineHistory = online.filter(OnlineLibraryEntry::hasHistory)
    val all = buildList<HistoryItem> {
        if (filter != HistoryFilter.ONLINE) addAll(local.map { HistoryItem.Local(it) })
        if (filter != HistoryFilter.LOCAL) addAll(onlineHistory.map { HistoryItem.Online(it) })
    }.sortedByDescending { it.timestamp }

    return HistoryUiState(
        items = all,
        filter = filter,
        localCount = local.size,
        onlineCount = onlineHistory.size,
    )
}

class HistoryViewModel(
    repository: LibraryRepository,
    onlineRepository: OnlineRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter.ALL)

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.observeHistory(),
        onlineRepository.libraryEntries,
        filter,
    ) { local, online, selectedFilter ->
        buildHistoryUiState(local, online.values, selectedFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )

    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository, onlineRepository) as T
    }
}
