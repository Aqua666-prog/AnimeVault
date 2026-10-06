package com.sergey.animevault.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadRepository
import com.sergey.animevault.data.online.OnlineRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val repository: DownloadRepository,
    onlineRepository: OnlineRepository? = null,
) : ViewModel() {
    val entries: StateFlow<List<DownloadEntry>> = repository.entries
        .map { list -> list.sortedByDescending(DownloadEntry::updatedAt) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Presentation-only poster lookup. Download persistence and workers are unchanged.
    val posters: StateFlow<Map<String, String>> = (onlineRepository?.libraryEntries?.map { library ->
        library.values.mapNotNull { entry -> entry.posterUrl?.takeIf(String::isNotBlank)?.let {
            "${entry.providerId}|${entry.releaseId}" to it
        } }.toMap()
    } ?: flowOf(emptyMap<String, String>())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun pause(id: String) {
        viewModelScope.launch { repository.pause(id) }
    }

    fun resume(id: String) {
        viewModelScope.launch { repository.resume(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }

    class Factory(
        private val repository: DownloadRepository,
        private val onlineRepository: OnlineRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DownloadsViewModel(repository, onlineRepository) as T
    }
}
