package com.sergey.animevault.ui.title

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.db.AnimeTitleEntity
import com.sergey.animevault.data.metadata.AniListMetadataCandidate
import com.sergey.animevault.data.metadata.AniListFranchiseGraph
import com.sergey.animevault.data.metadata.AniListFranchiseRepository
import com.sergey.animevault.data.metadata.FranchiseOrderMode
import com.sergey.animevault.data.metadata.AniListMetadataMatch
import com.sergey.animevault.data.metadata.MetadataMatchConfidence
import com.sergey.animevault.data.metadata.MetadataMatchEvidence
import com.sergey.animevault.data.metadata.rankAniListMetadataCandidates
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.model.GroupingTargetRow
import com.sergey.animevault.data.model.OfflineOnlineLinkRow
import com.sergey.animevault.data.model.TitleMetadataRow
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.scanner.EpisodeNameParser
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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


data class MetadataSearchUiState(
    val visible: Boolean = false,
    val query: String = "",
    val results: List<AniListMetadataMatch> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val autoChecking: Boolean = false,
    val autoAttempted: Boolean = false,
    val autoSuggestion: AniListMetadataMatch? = null,
    val autoErrorMessage: String? = null,
)

sealed interface FranchiseUiState {
    data object Idle : FranchiseUiState
    data object Loading : FranchiseUiState
    data class Ready(
        val graph: AniListFranchiseGraph,
        val orderMode: FranchiseOrderMode = FranchiseOrderMode.RELEASE,
    ) : FranchiseUiState
    data class Error(val message: String) : FranchiseUiState
}

data class TitleDetailUiState(
    val isLoading: Boolean = true,
    val title: AnimeTitleEntity? = null,
    val episodes: List<EpisodeRow> = emptyList(),
    val continueEpisodeId: Long? = null,
    val groupingTargets: List<GroupingTargetRow> = emptyList(),
    val selectedEpisodeIds: Set<Long> = emptySet(),
    val onlineLinks: List<OfflineOnlineLinkRow> = emptyList(),
    val metadata: TitleMetadataRow? = null,
    val linkSearch: OnlineLinkSearchUiState = OnlineLinkSearchUiState(),
    val metadataSearch: MetadataSearchUiState = MetadataSearchUiState(),
    val franchise: FranchiseUiState = FranchiseUiState.Idle,
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
    val metadata: TitleMetadataRow?,
)

class TitleDetailViewModel(
    private val titleId: Long,
    private val repository: LibraryRepository,
    private val onlineRepository: OnlineRepository,
    private val metadataRepository: AniListMetadataRepository,
    private val franchiseRepository: AniListFranchiseRepository,
) : ViewModel() {
    private val selectedEpisodes = MutableStateFlow<Set<Long>>(emptySet())
    private val linkSearch = MutableStateFlow(
        OnlineLinkSearchUiState(
            providers = onlineRepository.providers,
            selectedProviderId = onlineRepository.activeProviderId.value,
        ),
    )
    private val metadataSearch = MutableStateFlow(MetadataSearchUiState())
    private val message = MutableStateFlow<String?>(null)
    private val franchiseState = MutableStateFlow<FranchiseUiState>(FranchiseUiState.Idle)
    private val _events = MutableSharedFlow<TitleDetailEvent>()
    val events = _events.asSharedFlow()
    private var searchJob: Job? = null
    private var metadataSearchJob: Job? = null

    private val coreState = combine(
        repository.observeTitle(titleId),
        repository.observeEpisodes(titleId),
        repository.observeGroupingTargets(),
        repository.observeOnlineLinks(titleId),
        repository.observeTitleMetadata(titleId),
    ) { title, episodes, targets, links, metadata ->
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
            metadata = metadata,
        )
    }

    init {
        viewModelScope.launch {
            val initial = coreState.first { it.title != null && it.episodes.isNotEmpty() }
            if (initial.metadata == null) {
                runAutomaticMetadataMatch(
                    title = initial.title,
                    episodes = initial.episodes,
                    onlineLinks = initial.onlineLinks,
                )
            }
        }
        viewModelScope.launch {
            coreState
                .map { it.metadata?.externalId }
                .distinctUntilChanged()
                .collectLatest { anilistId ->
                    if (anilistId == null || anilistId <= 0L) {
                        franchiseState.value = FranchiseUiState.Idle
                    } else {
                        loadFranchise(anilistId)
                    }
                }
        }
    }

    val uiState: StateFlow<TitleDetailUiState> = combine(
        coreState,
        selectedEpisodes,
        linkSearch,
        metadataSearch,
        message,
    ) { core, selection, search, currentMetadataSearch, currentMessage ->
        TitleDetailUiState(
            isLoading = false,
            title = core.title,
            episodes = core.episodes,
            continueEpisodeId = core.continueEpisodeId,
            groupingTargets = core.groupingTargets,
            selectedEpisodeIds = selection.intersect(core.episodes.map(EpisodeRow::id).toSet()),
            onlineLinks = core.onlineLinks,
            metadata = core.metadata,
            linkSearch = search,
            metadataSearch = currentMetadataSearch,
            message = currentMessage,
        )
    }.combine(franchiseState) { state, franchise ->
        state.copy(franchise = franchise)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TitleDetailUiState(),
    )

    fun setFranchiseOrderMode(mode: FranchiseOrderMode) {
        val current = franchiseState.value as? FranchiseUiState.Ready ?: return
        franchiseState.value = current.copy(orderMode = mode)
    }

    fun retryFranchise() {
        val id = uiState.value.metadata?.externalId ?: return
        viewModelScope.launch { loadFranchise(id) }
    }

    private suspend fun loadFranchise(anilistId: Long) {
        franchiseState.value = FranchiseUiState.Loading
        franchiseState.value = runCatchingCancellable { franchiseRepository.load(anilistId) }
            .fold(
                onSuccess = { FranchiseUiState.Ready(it) },
                onFailure = { FranchiseUiState.Error(it.message ?: "Не удалось загрузить связи AniList") },
            )
    }

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

    fun openMetadataSearch() {
        metadataSearch.value = metadataSearch.value.copy(
            visible = true,
            query = metadataDefaultQuery(),
            results = emptyList(),
            isLoading = false,
            errorMessage = null,
        )
        performMetadataSearch(immediate = true)
    }

    fun closeMetadataSearch() {
        metadataSearchJob?.cancel()
        metadataSearch.value = metadataSearch.value.copy(visible = false, isLoading = false)
    }

    fun setMetadataSearchQuery(value: String) {
        metadataSearch.value = metadataSearch.value.copy(query = value, errorMessage = null)
        performMetadataSearch(immediate = false)
    }

    fun selectMetadata(candidate: AniListMetadataCandidate) {
        viewModelScope.launch {
            runCatchingCancellable { repository.saveAniListMetadata(titleId, candidate) }
                .onSuccess {
                    metadataSearch.value = metadataSearch.value.copy(autoSuggestion = null)
                    message.value = "Метаданные AniList сохранены"
                    closeMetadataSearch()
                }
                .onFailure { message.value = it.message ?: "Не удалось сохранить метаданные" }
        }
    }

    fun acceptAutomaticMetadataSuggestion() {
        val suggestion = metadataSearch.value.autoSuggestion ?: return
        viewModelScope.launch {
            runCatchingCancellable { repository.saveAniListMetadata(titleId, suggestion.candidate) }
                .onSuccess {
                    metadataSearch.value = metadataSearch.value.copy(autoSuggestion = null)
                    message.value = "Сопоставление AniList подтверждено"
                }
                .onFailure { message.value = it.message ?: "Не удалось сохранить метаданные" }
        }
    }

    fun dismissAutomaticMetadataSuggestion() {
        metadataSearch.value = metadataSearch.value.copy(autoSuggestion = null, autoAttempted = true)
    }

    fun retryAutomaticMetadataMatch() {
        val state = uiState.value
        if (state.title == null || state.metadata != null) return
        viewModelScope.launch {
            runAutomaticMetadataMatch(
                title = state.title,
                episodes = state.episodes,
                onlineLinks = state.onlineLinks,
            )
        }
    }

    fun clearMetadata() {
        viewModelScope.launch {
            repository.clearTitleMetadata(titleId)
            metadataSearch.value = metadataSearch.value.copy(
                autoAttempted = false,
                autoSuggestion = null,
                autoErrorMessage = null,
            )
            message.value = "Метаданные удалены"
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

    private fun metadataDefaultQuery(): String {
        val state = uiState.value
        return chooseMetadataSearchQuery(
            titleName = state.title?.name.orEmpty(),
            onlineAliases = state.onlineLinks.mapNotNull(OfflineOnlineLinkRow::onlineAlias),
            episodeFileNames = state.episodes.map(EpisodeRow::fileName),
        )
    }

    private fun performMetadataSearch(immediate: Boolean) {
        metadataSearchJob?.cancel()
        val snapshot = metadataSearch.value
        val query = snapshot.query.trim()
        if (query.length < 2) {
            metadataSearch.value = snapshot.copy(results = emptyList(), isLoading = false)
            return
        }
        metadataSearchJob = viewModelScope.launch {
            if (!immediate) delay(METADATA_SEARCH_DEBOUNCE_MS)
            val currentQuery = metadataSearch.value.query.trim()
            if (currentQuery.length < 2) return@launch
            metadataSearch.value = metadataSearch.value.copy(isLoading = true, errorMessage = null)
            runCatchingCancellable { metadataRepository.searchAnime(currentQuery) }
                .onSuccess { results ->
                    if (metadataSearch.value.query.trim() == currentQuery) {
                        val state = uiState.value
                        val ranked = rankAniListMetadataCandidates(
                            candidates = results,
                            evidence = metadataMatchEvidence(
                                title = state.title,
                                episodes = state.episodes,
                                onlineLinks = state.onlineLinks,
                            ),
                        )
                        metadataSearch.value = metadataSearch.value.copy(
                            results = ranked,
                            isLoading = false,
                            errorMessage = if (ranked.isEmpty()) "Совпадений не найдено" else null,
                        )
                    }
                }
                .onFailure { error ->
                    if (metadataSearch.value.query.trim() == currentQuery) {
                        metadataSearch.value = metadataSearch.value.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Не удалось получить данные AniList",
                        )
                    }
                }
        }
    }

    private suspend fun runAutomaticMetadataMatch(
        title: AnimeTitleEntity?,
        episodes: List<EpisodeRow>,
        onlineLinks: List<OfflineOnlineLinkRow>,
    ) {
        if (title == null || metadataSearch.value.autoChecking) return
        val evidence = metadataMatchEvidence(title, episodes, onlineLinks)
        val query = chooseMetadataSearchQuery(
            titleName = title.name,
            onlineAliases = onlineLinks.mapNotNull(OfflineOnlineLinkRow::onlineAlias),
            )
        if (query.length < 2 && evidence.linkedMalIds.isEmpty()) {
            metadataSearch.value = metadataSearch.value.copy(autoAttempted = true)
            return
        }

        metadataSearch.value = metadataSearch.value.copy(
            autoChecking = true,
            autoAttempted = false,
            autoSuggestion = null,
            autoErrorMessage = null,
        )
        runCatchingCancellable { findBestAutomaticMetadataMatch(evidence, query) }
            .onSuccess { best ->
                when {
                    best?.canAutoApply == true -> {
                        runCatchingCancellable {
                            repository.saveAniListMetadata(titleId, best.candidate)
                        }.onSuccess {
                            metadataSearch.value = metadataSearch.value.copy(
                                autoChecking = false,
                                autoAttempted = true,
                                autoSuggestion = null,
                                autoErrorMessage = null,
                            )
                            message.value = "AniList сопоставлен автоматически по MAL ID"
                        }.onFailure { error ->
                            metadataSearch.value = metadataSearch.value.copy(
                                autoChecking = false,
                                autoAttempted = true,
                                autoSuggestion = null,
                                autoErrorMessage = error.message ?: "Не удалось сохранить метаданные",
                            )
                        }
                    }
                    best?.confidence == MetadataMatchConfidence.HIGH -> {
                        metadataSearch.value = metadataSearch.value.copy(
                            autoChecking = false,
                            autoAttempted = true,
                            autoSuggestion = best,
                            autoErrorMessage = null,
                        )
                    }
                    else -> {
                        metadataSearch.value = metadataSearch.value.copy(
                            autoChecking = false,
                            autoAttempted = true,
                            autoSuggestion = null,
                            autoErrorMessage = null,
                        )
                    }
                }
            }
            .onFailure { error ->
                metadataSearch.value = metadataSearch.value.copy(
                    autoChecking = false,
                    autoAttempted = true,
                    autoSuggestion = null,
                    autoErrorMessage = error.message ?: "Автосопоставление AniList недоступно",
                )
            }
    }

    private suspend fun findBestAutomaticMetadataMatch(
        evidence: MetadataMatchEvidence,
        query: String,
    ): AniListMetadataMatch? {
        for (malId in evidence.linkedMalIds.take(MAX_DIRECT_MAL_LOOKUPS)) {
            val direct = metadataRepository.findAnimeByMalId(malId) ?: continue
            return rankAniListMetadataCandidates(listOf(direct), evidence).firstOrNull()
        }
        if (query.length < 2) return null
        return rankAniListMetadataCandidates(
            candidates = metadataRepository.searchAnime(query),
            evidence = evidence,
        ).firstOrNull()
    }

    private fun metadataMatchEvidence(
        title: AnimeTitleEntity?,
        episodes: List<EpisodeRow>,
        onlineLinks: List<OfflineOnlineLinkRow>,
    ): MetadataMatchEvidence = MetadataMatchEvidence(
        localTitle = title?.name.orEmpty(),
        onlineAliases = onlineLinks.mapNotNull(OfflineOnlineLinkRow::onlineAlias),
        onlineTitles = onlineLinks.map(OfflineOnlineLinkRow::onlineTitleName),
        episodeTitleHints = episodes.asSequence()
            .take(6)
            .map(EpisodeRow::fileName)
            .map(EpisodeNameParser::parse)
            .mapNotNull { it.titleHint?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .toList(),
        localEpisodeCount = episodes.size,
        linkedMalIds = onlineLinks.mapNotNull { it.malId?.trim()?.toLongOrNull() }.toSet(),
    )

    private fun isCurrentLinkSearch(query: String, providerId: String): Boolean =
        linkSearch.value.query.trim() == query && linkSearch.value.selectedProviderId == providerId

    class Factory(
        private val titleId: Long,
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
        private val metadataRepository: AniListMetadataRepository,
        private val franchiseRepository: AniListFranchiseRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TitleDetailViewModel(titleId, repository, onlineRepository, metadataRepository, franchiseRepository) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val METADATA_SEARCH_DEBOUNCE_MS = 450L
        const val MAX_DIRECT_MAL_LOOKUPS = 3
    }
}

internal fun chooseMetadataSearchQuery(
    titleName: String,
    onlineAliases: List<String>,
    episodeFileNames: List<String>,
): String {
    val alias = onlineAliases.asSequence()
        .map { it.replace('-', ' ').replace('_', ' ').trim() }
        .firstOrNull(String::isNotBlank)
    val fileHint = episodeFileNames.asSequence()
        .map(EpisodeNameParser::parse)
        .mapNotNull { it.titleHint?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
    return alias ?: fileHint ?: titleName.trim()
}
