package com.sergey.animevault.ui.title

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.db.AnimeTitleEntity
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.model.GroupingTargetRow
import com.sergey.animevault.data.model.OfflineOnlineLinkRow
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.ui.online.toNetworkMessage
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnlineLinkSearchUiState(
    val visible: Boolean = false,
    val providers: List<OnlineProviderDescriptor> = emptyList(),
    val selectedProviderId: String = "",
    val query: String = "",
    val results: List<OnlineReleaseCard> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class TitleDetailUiState(
    val isLoading: Boolean = true,
    val title: AnimeTitleEntity? = null,
    val episodes: List<EpisodeRow> = emptyList(),
    val continueEpisodeId: Long? = null,
    val groupingTargets: List<GroupingTargetRow> = emptyList(),
    val selectedEpisodeIds: Set<Long> = emptySet(),
    val onlineLinks: List<OfflineOnlineLinkRow> = emptyList(),
    val linkSearch: OnlineLinkSearchUiState = OnlineLinkSearchUiState(),
    val message: String? = null,
)

sealed interface TitleDetailEvent {
    data class OpenOfflineTitle(val titleId: Long) : TitleDetailEvent
}

private data class TitleDetailCoreState(
    val title: AnimeTitleEntity?,
    val episodes: List<EpisodeRow>,
    val continueEpisodeId: Long?,
    val groupingTargets: List<GroupingTargetRow>,
    val onlineLinks: List<OfflineOnlineLinkRow>,
)

class TitleDetailViewModel(
    private val titleId: Long,
    private val repository: LibraryRepository,
    private val onlineRepository: OnlineRepository,
) : ViewModel() {
    private val selectedEpisodes = MutableStateFlow<Set<Long>>(emptySet())
    private val linkSearch = MutableStateFlow(
        OnlineLinkSearchUiState(
            providers = onlineRepository.providers,
            selectedProviderId = onlineRepository.activeProviderId.value,
        ),
    )
    private val message = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<TitleDetailEvent>()
    val events = _events.asSharedFlow()
    private var searchJob: Job? = null

    private val coreState = combine(
        repository.observeTitle(titleId),
        repository.observeEpisodes(titleId),
        repository.observeGroupingTargets(),
        repository.observeOnlineLinks(titleId),
    ) { title, episodes, targets, links ->
        val partial = episodes
            .asSequence()
            .filter { !it.isCompleted && it.positionMs > 0L }
            .maxByOrNull { it.lastWatchedAt ?: 0L }
        val continueEpisode = partial
            ?: episodes.firstOrNull { !it.isCompleted }
            ?: episodes.firstOrNull()
        TitleDetailCoreState(
            title = title,
            episodes = episodes,
            continueEpisodeId = continueEpisode?.id,
            groupingTargets = targets.filter { target ->
                target.id != titleId && target.rootTreeUri == title?.rootTreeUri
            },
            onlineLinks = links,
        )
    }

    val uiState: StateFlow<TitleDetailUiState> = combine(
        coreState,
        selectedEpisodes,
        linkSearch,
        message,
    ) { core, selection, search, currentMessage ->
        TitleDetailUiState(
            isLoading = false,
            title = core.title,
            episodes = core.episodes,
            continueEpisodeId = core.continueEpisodeId,
            groupingTargets = core.groupingTargets,
            selectedEpisodeIds = selection.intersect(core.episodes.map(EpisodeRow::id).toSet()),
            onlineLinks = core.onlineLinks,
            linkSearch = search,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TitleDetailUiState(),
    )

    fun setPoster(uri: Uri) {
        viewModelScope.launch { repository.setTitlePoster(titleId, uri) }
    }

    fun toggleEpisodeSelection(episodeId: Long) {
        selectedEpisodes.value = selectedEpisodes.value.toMutableSet().apply {
            if (!add(episodeId)) remove(episodeId)
        }
    }

    fun clearEpisodeSelection() {
        selectedEpisodes.value = emptySet()
    }

    fun mergeSelectedInto(targetTitleId: Long) {
        val selected = selectedEpisodes.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { repository.mergeEpisodesIntoTitle(selected, targetTitleId) }
                .onSuccess {
                    selectedEpisodes.value = emptySet()
                    message.value = "Серии объединены. Решение сохранится после пересканирования."
                    _events.emit(TitleDetailEvent.OpenOfflineTitle(targetTitleId))
                }
                .onFailure { message.value = it.message ?: "Не удалось объединить серии" }
        }
    }

    fun separateSelected(newTitleName: String) {
        val selected = selectedEpisodes.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { repository.separateEpisodes(selected, newTitleName) }
                .onSuccess { newTitleId ->
                    selectedEpisodes.value = emptySet()
                    message.value = "Создан отдельный тайтл"
                    _events.emit(TitleDetailEvent.OpenOfflineTitle(newTitleId))
                }
                .onFailure { message.value = it.message ?: "Не удалось отделить серии" }
        }
    }

    fun restoreAutomaticGrouping() {
        val selected = selectedEpisodes.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { repository.restoreAutomaticGrouping(selected) }
                .onSuccess {
                    selectedEpisodes.value = emptySet()
                    message.value = "Для выбранных файлов восстановлена автоматическая группировка"
                }
                .onFailure { message.value = it.message ?: "Не удалось восстановить группировку" }
        }
    }

    fun openOnlineLinkSearch() {
        val titleName = uiState.value.title?.name.orEmpty()
        linkSearch.value = linkSearch.value.copy(
            visible = true,
            query = titleName,
            results = emptyList(),
            errorMessage = null,
        )
        performOnlineSearch(immediate = true)
    }

    fun closeOnlineLinkSearch() {
        searchJob?.cancel()
        linkSearch.value = linkSearch.value.copy(visible = false)
    }

    fun setOnlineLinkQuery(value: String) {
        linkSearch.value = linkSearch.value.copy(query = value, errorMessage = null)
        performOnlineSearch(immediate = false)
    }

    fun selectOnlineLinkProvider(providerId: String) {
        linkSearch.value = linkSearch.value.copy(
            selectedProviderId = providerId,
            results = emptyList(),
            errorMessage = null,
        )
        performOnlineSearch(immediate = true)
    }

    fun linkOnlineRelease(release: OnlineReleaseCard) {
        viewModelScope.launch {
            runCatchingCancellable { repository.linkOnlineTitle(titleId, release) }
                .onSuccess {
                    message.value = "Связано с ${release.providerName}: ${release.name}"
                    closeOnlineLinkSearch()
                }
                .onFailure { message.value = it.message ?: "Не удалось сохранить связь" }
        }
    }

    fun unlinkOnlineRelease(link: OfflineOnlineLinkRow) {
        viewModelScope.launch {
            repository.unlinkOnlineTitle(titleId, link.providerId, link.onlineReleaseId)
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun performOnlineSearch(immediate: Boolean) {
        searchJob?.cancel()
        val snapshot = linkSearch.value
        if (snapshot.query.isBlank() || snapshot.selectedProviderId.isBlank()) {
            linkSearch.value = snapshot.copy(results = emptyList(), isLoading = false)
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            val current = linkSearch.value
            val query = current.query.trim()
            val providerId = current.selectedProviderId
            linkSearch.value = current.copy(isLoading = true, errorMessage = null)
            runCatchingCancellable {
                onlineRepository.getCatalog(
                    providerId = providerId,
                    page = 1,
                    limit = 24,
                    search = query,
                )
            }.onSuccess { page ->
                if (isCurrentLinkSearch(query, providerId)) {
                    linkSearch.value = linkSearch.value.copy(
                        results = page.releases,
                        isLoading = false,
                    )
                }
            }.onFailure { error ->
                if (!isCurrentLinkSearch(query, providerId)) return@onFailure
                linkSearch.value = linkSearch.value.copy(
                    isLoading = false,
                    errorMessage = error.toNetworkMessage(
                        onlineRepository.descriptor(providerId).name,
                    ),
                )
            }
        }
    }

    private fun isCurrentLinkSearch(query: String, providerId: String): Boolean =
        linkSearch.value.query.trim() == query && linkSearch.value.selectedProviderId == providerId

    class Factory(
        private val titleId: Long,
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TitleDetailViewModel(titleId, repository, onlineRepository) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
