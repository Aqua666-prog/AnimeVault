package com.sergey.animevault.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadRepository
import com.sergey.animevault.data.download.DownloadStatus
import com.sergey.animevault.data.download.chooseDownloadStream
import com.sergey.animevault.data.metadata.AnimeThemeInfo
import com.sergey.animevault.data.metadata.AnimeThemeRepository
import com.sergey.animevault.data.model.LinkedLocalTitleSummary
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineTranslationOption
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.online.translationOptions
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnlineTitleUiState(
    val isLoading: Boolean = true,
    val release: OnlineReleaseDetails? = null,
    val progress: Map<String, OnlineWatchProgress> = emptyMap(),
    val continueEpisodeId: String? = null,
    val translationOptions: List<OnlineTranslationOption> = emptyList(),
    val selectedTranslationKey: String? = null,
    val isFavorite: Boolean = false,
    val themes: AnimeThemeInfo? = null,
    val isThemesLoading: Boolean = false,
    val themesMessage: String? = null,
    val linkedLocalTitle: LinkedLocalTitleSummary? = null,
    val downloadsByEpisode: Map<String, DownloadEntry> = emptyMap(),
    val downloadMessage: String? = null,
    val errorMessage: String? = null,
)

class OnlineTitleViewModel(
    private val providerId: String,
    private val releaseId: String,
    private val repository: OnlineRepository,
    private val themeRepository: AnimeThemeRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    private val loadState = MutableStateFlow<OnlineTitleLoadState>(OnlineTitleLoadState.Loading)
    private val themeState = MutableStateFlow<OnlineThemeLoadState>(OnlineThemeLoadState.Idle)
    private val linkedLocalTitle = MutableStateFlow<LinkedLocalTitleSummary?>(null)
    private val providerName = repository.descriptor(providerId).name
    private val downloadMessage = MutableStateFlow<String?>(null)
    private var themeJob: Job? = null

    val uiState: StateFlow<OnlineTitleUiState> = combine(
        loadState,
        themeState,
        repository.progress,
        repository.preferredTranslations,
        repository.libraryEntries,
    ) { load, themes, _, _, _ ->
        val progress = repository.progressFor(providerId)
        when (load) {
            OnlineTitleLoadState.Loading -> OnlineTitleUiState(isLoading = true, progress = progress)
            is OnlineTitleLoadState.Error -> OnlineTitleUiState(
                isLoading = false,
                progress = progress,
                errorMessage = load.message,
            )
            is OnlineTitleLoadState.Ready -> {
                val translationOptions = load.release.translationOptions()
                val preferredTranslation = repository.preferredTranslation(providerId, releaseId)
                    ?.takeIf { preferred -> translationOptions.any { it.key == preferred } }
                val playable = load.release.episodes.filter { it.hasStream }
                val partial = playable
                    .filter { episode ->
                        progress[episode.id]?.let { !it.isCompleted && it.positionMs > 0L } == true
                    }
                    .maxByOrNull { progress[it.id]?.lastWatchedAt ?: 0L }
                val next = partial
                    ?: playable.firstOrNull { progress[it.id]?.isCompleted != true }
                    ?: playable.firstOrNull()
                OnlineTitleUiState(
                    isLoading = false,
                    release = load.release,
                    progress = progress,
                    continueEpisodeId = next?.id,
                    translationOptions = translationOptions,
                    selectedTranslationKey = preferredTranslation,
                    isFavorite = repository.libraryEntry(providerId, releaseId)?.isFavorite == true,
                    themes = (themes as? OnlineThemeLoadState.Ready)?.value,
                    isThemesLoading = themes is OnlineThemeLoadState.Loading,
                    themesMessage = when (themes) {
                        is OnlineThemeLoadState.Error -> themes.message
                        else -> null
                    },
                )
            }
        }
    }.combine(linkedLocalTitle) { state, linkedLocal ->
        state.copy(linkedLocalTitle = linkedLocal)
    }.combine(downloadRepository.entries) { state, downloads ->
        val byEpisode = downloads
            .asSequence()
            .filter { it.providerId == providerId && it.releaseId == releaseId }
            .groupBy(DownloadEntry::episodeId)
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<DownloadEntry> { downloadStatusRank(it.status) }
                        .thenBy(DownloadEntry::updatedAt),
                )!!
            }
        state.copy(downloadsByEpisode = byEpisode)
    }.combine(downloadMessage) { state, message ->
        state.copy(downloadMessage = message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnlineTitleUiState(),
    )

    init {
        retry()
    }

    fun retry() {
        viewModelScope.launch {
            themeJob?.cancel()
            themeState.value = OnlineThemeLoadState.Idle
            linkedLocalTitle.value = null
            loadState.value = OnlineTitleLoadState.Loading
            loadState.value = runCatchingCancellable { repository.getRelease(providerId, releaseId) }
                .fold(
                    onSuccess = { release ->
                        repository.markReleaseOpened(release)
                        linkedLocalTitle.value = runCatchingCancellable {
                            libraryRepository.findLinkedLocalTitleSummary(providerId, releaseId)
                        }.getOrNull()
                        loadThemes(release)
                        OnlineTitleLoadState.Ready(release)
                    },
                    onFailure = { OnlineTitleLoadState.Error(it.toNetworkMessage(providerName)) },
                )
        }
    }

    fun retryThemes() {
        val release = (loadState.value as? OnlineTitleLoadState.Ready)?.release ?: return
        loadThemes(release)
    }

    fun selectTranslation(translationKey: String?) {
        repository.setPreferredTranslation(providerId, releaseId, translationKey)
    }

    fun toggleFavorite() {
        val release = (loadState.value as? OnlineTitleLoadState.Ready)?.release ?: return
        val favorite = repository.libraryEntry(providerId, releaseId)?.isFavorite == true
        repository.setFavorite(release, !favorite)
    }

    fun downloadEpisode(episodeId: String) {
        val release = (loadState.value as? OnlineTitleLoadState.Ready)?.release ?: return
        val episode = release.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            downloadMessage.value = null
            runCatchingCancellable {
                val streams = repository.resolveStreams(providerId, releaseId, episode)
                val selected = chooseDownloadStream(
                    streams = streams,
                    preferredTranslationKey = repository.preferredTranslation(providerId, releaseId),
                    preferredQuality = repository.preferredQuality(providerId, releaseId),
                ) ?: throw IllegalStateException("Источник не дал прямой MP4/HLS-поток для загрузки")
                downloadRepository.enqueue(release, episode, selected)
            }.onFailure { error ->
                downloadMessage.value = error.message ?: "Не удалось поставить серию в очередь"
            }
        }
    }

    fun pauseDownload(episodeId: String) {
        uiState.value.downloadsByEpisode[episodeId]?.let { downloadRepository.pause(it.id) }
    }

    fun resumeDownload(episodeId: String) {
        uiState.value.downloadsByEpisode[episodeId]?.let { downloadRepository.resume(it.id) }
    }

    fun removeDownload(episodeId: String) {
        uiState.value.downloadsByEpisode[episodeId]?.let { downloadRepository.remove(it.id) }
    }

    fun clearDownloadMessage() {
        downloadMessage.value = null
    }

    private fun loadThemes(release: OnlineReleaseDetails) {
        themeJob?.cancel()
        themeJob = viewModelScope.launch {
            themeState.value = OnlineThemeLoadState.Loading
            themeState.value = runCatchingCancellable { themeRepository.getThemes(release) }
                .fold(
                    onSuccess = { OnlineThemeLoadState.Ready(it) },
                    onFailure = {
                        OnlineThemeLoadState.Error(
                            "AnimeThemes временно недоступен. Попробуйте повторить запрос позже.",
                        )
                    },
                )
        }
    }

    class Factory(
        private val providerId: String,
        private val releaseId: String,
        private val repository: OnlineRepository,
        private val themeRepository: AnimeThemeRepository,
        private val libraryRepository: LibraryRepository,
        private val downloadRepository: DownloadRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnlineTitleViewModel(
                providerId,
                releaseId,
                repository,
                themeRepository,
                libraryRepository,
                downloadRepository,
            ) as T
    }
}

private sealed interface OnlineTitleLoadState {
    data object Loading : OnlineTitleLoadState
    data class Ready(val release: OnlineReleaseDetails) : OnlineTitleLoadState
    data class Error(val message: String) : OnlineTitleLoadState
}

private sealed interface OnlineThemeLoadState {
    data object Idle : OnlineThemeLoadState
    data object Loading : OnlineThemeLoadState
    data class Ready(val value: AnimeThemeInfo) : OnlineThemeLoadState
    data class Error(val message: String) : OnlineThemeLoadState
}

private fun downloadStatusRank(status: DownloadStatus): Int = when (status) {
    DownloadStatus.COMPLETED -> 6
    DownloadStatus.DOWNLOADING -> 5
    DownloadStatus.QUEUED -> 4
    DownloadStatus.PAUSED -> 3
    DownloadStatus.FAILED -> 2
    DownloadStatus.REMOVING -> 1
}
