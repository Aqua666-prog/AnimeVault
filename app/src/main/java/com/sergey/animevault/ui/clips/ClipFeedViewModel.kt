package com.sergey.animevault.ui.clips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.clips.ClipPreferenceStore
import com.sergey.animevault.data.metadata.AnimeThemesClipRepository
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.util.runCatchingCancellable
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ClipFeedItem(
    val providerId: String,
    val releaseId: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val type: String?,
    val genres: List<String>,
    val videoUrl: String? = null,
    val clipLabel: String? = null,
    val clipResolution: Int? = null,
    val clipStartMs: Long = DEFAULT_CLIP_START_MS,
    val clipEndMs: Long = DEFAULT_CLIP_END_MS,
    val previewResolved: Boolean = false,
) {
    val stableKey: String
        get() = "$providerId:$releaseId"

    val playbackRequest: ClipPlaybackRequest?
        get() = videoUrl?.let { url ->
            ClipPlaybackRequest(
                url = url,
                startPositionMs = clipStartMs,
                endPositionMs = clipEndMs,
            )
        }

    companion object {
        private const val DEFAULT_CLIP_START_MS = 10_000L
        private const val DEFAULT_CLIP_END_MS = 35_000L
    }
}

data class ClipFeedUiState(
    val isLoading: Boolean = true,
    val clips: List<ClipFeedItem> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

sealed interface ClipFeedEvent {
    data class Play(
        val providerId: String,
        val releaseId: String,
        val episodeId: String,
    ) : ClipFeedEvent

    data class Message(val text: String) : ClipFeedEvent
}

class ClipFeedViewModel(
    private val onlineRepository: OnlineRepository,
    private val clipRepository: AnimeThemesClipRepository,
    private val preferenceStore: ClipPreferenceStore,
) : ViewModel() {
    private val feedState = MutableStateFlow(ClipFeedUiState())
    private val resolvingPreviewKeys = mutableSetOf<String>()
    private val resolvedPreviewKeys = mutableSetOf<String>()
    private val _events = MutableSharedFlow<ClipFeedEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ClipFeedEvent> = _events.asSharedFlow()

    val uiState: StateFlow<ClipFeedUiState> = combine(
        feedState,
        onlineRepository.libraryEntries,
    ) { state, entries ->
        state.copy(
            favoriteKeys = entries.values
                .asSequence()
                .filter { it.isFavorite }
                .map { "${it.providerId}:${it.releaseId}" }
                .toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ClipFeedUiState(),
    )

    init {
        load()
    }

    fun retry() = load()

    fun onPageSelected(index: Int) {
        val item = feedState.value.clips.getOrNull(index) ?: return
        preferenceStore.recordImpression(item.stableKey)
        ensurePreviewWindow(index)
    }

    fun recordWatch(item: ClipFeedItem, durationMs: Long) {
        preferenceStore.recordWatch(item.genres, durationMs.coerceAtLeast(0L))
    }

    fun recordOpened(item: ClipFeedItem) {
        preferenceStore.recordOpened(item.genres)
    }

    fun notInterested(item: ClipFeedItem) {
        preferenceStore.recordNotInterested(item.genres)
        _events.tryEmit(ClipFeedEvent.Message("Похожих тайтлов будет немного меньше"))
    }

    fun toggleFavorite(item: ClipFeedItem) {
        viewModelScope.launch {
            val current = onlineRepository.libraryEntry(item.providerId, item.releaseId)?.isFavorite == true
            runCatchingCancellable {
                val release = onlineRepository.getRelease(item.providerId, item.releaseId)
                onlineRepository.setFavorite(release, !current)
                preferenceStore.recordFavorite(item.genres, !current)
            }.onFailure { error ->
                _events.emit(
                    ClipFeedEvent.Message(
                        error.message ?: "Не удалось изменить список",
                    ),
                )
            }
        }
    }

    fun play(item: ClipFeedItem) {
        viewModelScope.launch {
            runCatchingCancellable {
                val release = onlineRepository.getRelease(item.providerId, item.releaseId)
                val progress = onlineRepository.progressFor(item.providerId)
                val playable = release.episodes.filter { it.hasStream }
                val partial = playable
                    .filter { episode ->
                        progress[episode.id]?.let { value ->
                            !value.isCompleted && value.positionMs > 0L
                        } == true
                    }
                    .maxByOrNull { episode -> progress[episode.id]?.lastWatchedAt ?: 0L }
                val episode = partial
                    ?: playable.firstOrNull { candidate -> progress[candidate.id]?.isCompleted != true }
                    ?: playable.firstOrNull()
                    ?: error("У тайтла пока нет доступных серий")
                preferenceStore.recordPlayback(item.genres)
                ClipFeedEvent.Play(
                    providerId = item.providerId,
                    releaseId = item.releaseId,
                    episodeId = episode.id,
                )
            }.fold(
                onSuccess = { event -> _events.emit(event) },
                onFailure = { error ->
                    _events.emit(
                        ClipFeedEvent.Message(
                            error.message ?: "Не удалось открыть серию",
                        ),
                    )
                },
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            resolvingPreviewKeys.clear()
            resolvedPreviewKeys.clear()
            feedState.value = ClipFeedUiState(isLoading = true)
            feedState.value = runCatchingCancellable {
                val page = onlineRepository.getCatalog(
                    providerId = OnlineProviderIds.UNIFIED,
                    page = 1,
                    limit = FEED_LIMIT,
                )
                val clips = page.releases
                    .distinctBy { "${it.providerId}:${it.id}" }
                    .map(OnlineReleaseCard::toClipFeedItem)
                    .sortedByDescending { item ->
                        preferenceStore.score(
                            itemKey = item.stableKey,
                            genres = item.genres,
                            year = item.year,
                        )
                    }
                ClipFeedUiState(
                    isLoading = false,
                    clips = clips,
                )
            }.getOrElse { error ->
                ClipFeedUiState(
                    isLoading = false,
                    errorMessage = error.message ?: "Не удалось загрузить ленту клипов",
                )
            }
            if (feedState.value.clips.isNotEmpty()) ensurePreviewWindow(0)
        }
    }

    private fun ensurePreviewWindow(index: Int) {
        val clips = feedState.value.clips
        for (target in (index - PREVIEW_BEHIND)..(index + PREVIEW_AHEAD)) {
            clips.getOrNull(target)?.let(::resolvePreview)
        }
    }

    private fun resolvePreview(item: ClipFeedItem) {
        val key = item.stableKey
        if (key in resolvedPreviewKeys || !resolvingPreviewKeys.add(key)) return
        viewModelScope.launch {
            val preview = runCatchingCancellable {
                val release = onlineRepository.getRelease(item.providerId, item.releaseId)
                clipRepository.getClip(release)
            }.getOrNull()

            val start = CLIP_MIN_START_MS +
                (item.stableKey.hashCode().toLong().absoluteValue % CLIP_START_VARIATION_MS)
            feedState.value = feedState.value.copy(
                clips = feedState.value.clips.map { current ->
                    if (current.stableKey != key) {
                        current
                    } else {
                        current.copy(
                            videoUrl = preview?.url,
                            clipLabel = preview?.label,
                            clipResolution = preview?.resolution,
                            clipStartMs = start,
                            clipEndMs = start + CLIP_LENGTH_MS,
                            previewResolved = true,
                        )
                    }
                },
            )
            resolvingPreviewKeys.remove(key)
            resolvedPreviewKeys.add(key)
        }
    }

    class Factory(
        private val onlineRepository: OnlineRepository,
        private val clipRepository: AnimeThemesClipRepository,
        private val preferenceStore: ClipPreferenceStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ClipFeedViewModel::class.java))
            return ClipFeedViewModel(onlineRepository, clipRepository, preferenceStore) as T
        }
    }

    private companion object {
        const val FEED_LIMIT = 42
        const val PREVIEW_BEHIND = 1
        const val PREVIEW_AHEAD = 2
        const val CLIP_MIN_START_MS = 8_000L
        const val CLIP_START_VARIATION_MS = 10_000L
        const val CLIP_LENGTH_MS = 25_000L
    }
}

private fun OnlineReleaseCard.toClipFeedItem(): ClipFeedItem = ClipFeedItem(
    providerId = providerId,
    releaseId = id,
    title = name,
    posterUrl = posterUrl,
    year = year,
    type = type,
    genres = genres.take(4),
)
