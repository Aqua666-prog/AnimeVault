package com.sergey.animevault.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineLibraryEntry
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.playback.PlaybackFailureClassifier
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlineCatalogUiState(
    val query: String = "",
    val providers: List<OnlineProviderDescriptor> = emptyList(),
    val selectedProviderId: String = "",
    val selectedProviderName: String = "",
    val searchHint: String = "Найти аниме",
    val releases: List<OnlineReleaseCard> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val currentPage: Int = 0,
    val errorMessage: String? = null,
    val selectedGenre: String? = null,
    val selectedCollection: ThematicCollection = ThematicCollection.ALL,
    val sort: CatalogSort = CatalogSort.SOURCE,
    val selectedYear: Int? = null,
    val selectedType: String? = null,
    val statusFilter: CatalogStatusFilter = CatalogStatusFilter.ALL,
    val episodeFilter: CatalogEpisodeFilter = CatalogEpisodeFilter.ANY,
    val layout: CatalogLayout = CatalogLayout.GRID,
    val continueWatching: List<OnlineLibraryEntry> = emptyList(),
) {
    val availableGenres: List<String> get() = availableCatalogGenres(releases)
    val availableYears: List<Int> get() = availableCatalogYears(releases)
    val availableTypes: List<String> get() = availableCatalogTypes(releases)
    val collections: List<CollectionOption> get() = availableCollections(releases)
    val visibleReleases: List<OnlineReleaseCard> get() = discoverCatalog(
        releases = releases,
        selectedGenre = selectedGenre,
        collection = selectedCollection,
        sort = sort,
        selectedYear = selectedYear,
        selectedType = selectedType,
        status = statusFilter,
        episodes = episodeFilter,
    )
    val hasDiscoverySelection: Boolean get() =
        selectedGenre != null || selectedCollection != ThematicCollection.ALL || sort != CatalogSort.SOURCE ||
            selectedYear != null || selectedType != null || statusFilter != CatalogStatusFilter.ALL ||
            episodeFilter != CatalogEpisodeFilter.ANY
}

class OnlineCatalogViewModel(
    private val repository: OnlineRepository,
) : ViewModel() {
    private val initialProvider = repository.descriptor(repository.activeProviderId.value)
    private val _uiState = MutableStateFlow(
        OnlineCatalogUiState(
            providers = repository.providers,
            selectedProviderId = initialProvider.id,
            selectedProviderName = initialProvider.name,
            searchHint = initialProvider.searchHint,
        ),
    )
    val uiState: StateFlow<OnlineCatalogUiState> = _uiState.asStateFlow()

    private var currentPage = 0
    private var totalPages = 1
    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            repository.libraryEntries.collect { entries ->
                val values = entries.values
                _uiState.update { state ->
                    state.copy(
                        continueWatching = values
                            .filter(OnlineLibraryEntry::hasContinueProgress)
                            .sortedByDescending { it.lastWatchedAt }
                            .take(12),
                    )
                }
            }
        }
        refresh()
    }

    fun selectProvider(providerId: String) {
        if (providerId == _uiState.value.selectedProviderId) return
        repository.selectProvider(providerId)
        val provider = repository.descriptor(providerId)
        requestJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                selectedProviderId = provider.id,
                selectedProviderName = provider.name,
                searchHint = provider.searchHint,
                releases = emptyList(),
                currentPage = 0,
                errorMessage = null,
                selectedGenre = null,
                selectedCollection = ThematicCollection.ALL,
                sort = CatalogSort.SOURCE,
                selectedYear = null,
                selectedType = null,
                statusFilter = CatalogStatusFilter.ALL,
                episodeFilter = CatalogEpisodeFilter.ANY,
            )
        }
        refresh()
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value, errorMessage = null) }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadFirstPage()
        }
    }

    fun refresh() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch { loadFirstPage() }
    }

    fun selectGenre(genre: String?) {
        _uiState.update { it.copy(selectedGenre = genre) }
    }

    fun selectCollection(collection: ThematicCollection) {
        _uiState.update { it.copy(selectedCollection = collection) }
    }

    fun selectSort(sort: CatalogSort) {
        _uiState.update { it.copy(sort = sort) }
    }

    fun selectYear(year: Int?) {
        _uiState.update { it.copy(selectedYear = year) }
    }

    fun selectType(type: String?) {
        _uiState.update { it.copy(selectedType = type) }
    }

    fun selectStatus(status: CatalogStatusFilter) {
        _uiState.update { it.copy(statusFilter = status) }
    }

    fun selectEpisodeFilter(filter: CatalogEpisodeFilter) {
        _uiState.update { it.copy(episodeFilter = filter) }
    }

    fun toggleLayout() {
        _uiState.update { state ->
            state.copy(layout = if (state.layout == CatalogLayout.GRID) CatalogLayout.LIST else CatalogLayout.GRID)
        }
    }

    fun resetDiscovery() {
        _uiState.update {
            it.copy(
                selectedGenre = null,
                selectedCollection = ThematicCollection.ALL,
                sort = CatalogSort.SOURCE,
                selectedYear = null,
                selectedType = null,
                statusFilter = CatalogStatusFilter.ALL,
                episodeFilter = CatalogEpisodeFilter.ANY,
            )
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.canLoadMore) return
        val querySnapshot = state.query.trim()
        val providerSnapshot = state.selectedProviderId
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        requestJob = viewModelScope.launch {
            runCatchingCancellable {
                repository.getCatalog(
                    providerId = providerSnapshot,
                    page = currentPage + 1,
                    search = querySnapshot,
                )
            }.onSuccess { page ->
                if (!isCurrentRequest(providerSnapshot, querySnapshot)) return@onSuccess
                currentPage = page.currentPage
                totalPages = page.totalPages
                _uiState.update {
                    it.copy(
                        releases = (it.releases + page.releases).distinctBy { release ->
                            "${release.providerId}|${release.id}"
                        },
                        isLoadingMore = false,
                        canLoadMore = currentPage < totalPages,
                        currentPage = currentPage,
                    )
                }
            }.onFailure { error ->
                if (!isCurrentRequest(providerSnapshot, querySnapshot)) return@onFailure
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        errorMessage = error.toNetworkMessage(state.selectedProviderName),
                    )
                }
            }
        }
    }

    private suspend fun loadFirstPage() {
        val state = _uiState.value
        val querySnapshot = state.query.trim()
        val providerSnapshot = state.selectedProviderId
        val providerName = state.selectedProviderName
        _uiState.update {
            it.copy(
                releases = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                canLoadMore = false,
                currentPage = 0,
                errorMessage = null,
            )
        }
        runCatchingCancellable {
            repository.getCatalog(providerId = providerSnapshot, page = 1, search = querySnapshot)
        }.onSuccess { page ->
            if (!isCurrentRequest(providerSnapshot, querySnapshot)) return@onSuccess
            currentPage = page.currentPage
            totalPages = page.totalPages
            _uiState.update {
                it.copy(
                    releases = page.releases,
                    isLoading = false,
                    canLoadMore = currentPage < totalPages,
                    currentPage = currentPage,
                )
            }
        }.onFailure { error ->
            if (!isCurrentRequest(providerSnapshot, querySnapshot)) return@onFailure
            _uiState.update {
                it.copy(isLoading = false, errorMessage = error.toNetworkMessage(providerName))
            }
        }
    }

    private fun isCurrentRequest(providerId: String, query: String): Boolean =
        _uiState.value.selectedProviderId == providerId && _uiState.value.query.trim() == query

    class Factory(
        private val repository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnlineCatalogViewModel(repository) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
    }
}

internal fun Throwable.toNetworkMessage(sourceName: String): String = when (this) {
    is OnlineSourceException -> message ?: "Ошибка источника $sourceName"
    else -> PlaybackFailureClassifier.classify(this).userMessage(sourceName)
}
