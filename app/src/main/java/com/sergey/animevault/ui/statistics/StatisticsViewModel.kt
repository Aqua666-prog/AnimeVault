package com.sergey.animevault.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class StatisticsViewModel(
    repository: LibraryRepository,
    onlineRepository: OnlineRepository,
) : ViewModel() {
    val uiState: StateFlow<StatisticsSnapshot> = combine(
        repository.observeLibrary(),
        repository.observeHistory(),
        repository.observeAllTitleMetadata(),
        onlineRepository.libraryEntries,
    ) { titles, history, metadata, online ->
        buildStatisticsSnapshot(titles, history, metadata, online.values)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsSnapshot(),
    )

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StatisticsViewModel::class.java))
            return StatisticsViewModel(repository, onlineRepository) as T
        }
    }
}
