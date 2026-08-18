package com.sergey.animevault.ui.online

import androidx.lifecycle.ViewModel
import com.sergey.animevault.data.anilist.AniListSyncRepository
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.online.prioritizePlaybackPreferences
import com.sergey.animevault.data.playback.EpisodePlaybackPlan
import com.sergey.animevault.data.playback.PlaybackProgressMerger
import com.sergey.animevault.data.playback.PlaybackProgressSnapshot
import com.sergey.animevault.data.playback.PlaybackVariant
import com.sergey.animevault.data.playback.PlaybackSession
import com.sergey.animevault.data.playback.PlaybackSessionEvent
import com.sergey.animevault.data.playback.PlaybackSessionStore
import com.sergey.animevault.data.playback.buildOnlineEpisodePlaybackPlan
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlinePlaybackBundle(
    val providerId: String,
    val providerName: String,
    val releaseId: String,
    val releaseName: String,
    val episode: OnlineEpisode,
    val episodes: List<OnlineEpisode>,
    val progress: OnlineWatchProgress,
    val episodeProgress: Map<String, OnlineWatchProgress>,
    val nextEpisodeId: String?,
    val localVariants: List<PlaybackVariant> = emptyList(),
    val localProgress: PlaybackProgressSnapshot? = null,
) {
    /** Same episode/variant contract as the offline player. */
    val playbackPlan: EpisodePlaybackPlan
        get() {
            val onlinePlan = buildOnlineEpisodePlaybackPlan(
                providerId = providerId,
                providerName = providerName,
                releaseId = releaseId,
                releaseName = releaseName,
                episode = episode,
                progress = progress.toPlaybackProgressSnapshot(),
                nextEpisodeId = nextEpisodeId,
            )
            val retargetedLocal = localVariants.map { variant ->
                variant.copy(episodeKey = onlinePlan.episodeKey)
            }
            return onlinePlan.copy(
                variants = (retargetedLocal + onlinePlan.variants).distinctBy(PlaybackVariant::key),
                progress = PlaybackProgressMerger.choose(onlinePlan.progress, localProgress),
            )
        }
}

private fun OnlineWatchProgress.toPlaybackProgressSnapshot(): PlaybackProgressSnapshot =
    PlaybackProgressSnapshot(
        positionMs = positionMs,
        durationMs = durationMs,
        isCompleted = isCompleted,
        lastWatchedAt = lastWatchedAt,
    )

private fun PlaybackProgressSnapshot.toOnlineWatchProgress(): OnlineWatchProgress =
    OnlineWatchProgress(
        positionMs = positionMs,
        durationMs = durationMs,
        isCompleted = isCompleted,
        lastWatchedAt = lastWatchedAt,
    )

sealed interface OnlinePlayerUiState {
    data object Loading : OnlinePlayerUiState
    data class Ready(val playback: OnlinePlaybackBundle) : OnlinePlayerUiState
    data class Error(val message: String) : OnlinePlayerUiState
}

class OnlinePlayerViewModel(
    private val providerId: String,
    private val releaseId: String,
    private val episodeId: String,
    private val repository: OnlineRepository,
    private val libraryRepository: LibraryRepository,
    private val aniListSyncRepository: AniListSyncRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<OnlinePlayerUiState>(OnlinePlayerUiState.Loading)
    val uiState: StateFlow<OnlinePlayerUiState> = _uiState.asStateFlow()

    private val playbackSessionStore = PlaybackSessionStore()
    val playbackSession: StateFlow<PlaybackSession> = playbackSessionStore.state

    private var loadedRelease: OnlineReleaseDetails? = null
    private var syncedCompletion = false

    init {
        viewModelScope.launch {
            val providerName = repository.descriptor(providerId).name
            _uiState.value = runCatchingCancellable {
                val release = repository.getRelease(providerId, releaseId)
                loadedRelease = release
                repository.markReleaseOpened(release)
                val playable = release.episodes.filter(OnlineEpisode::hasStream)
                val index = playable.indexOfFirst { it.id == episodeId }
                val episode = playable.getOrNull(index)
                    ?: throw IllegalStateException("Серия недоступна для онлайн-просмотра")
                val localPlayback = libraryRepository.findLinkedLocalPlayback(
                    providerId = providerId,
                    releaseId = releaseId,
                    episodeOrdinal = episode.ordinal,
                )
                val streams = runCatchingCancellable {
                    repository.resolveStreams(providerId, releaseId, episode)
                        .prioritizePlaybackPreferences(
                            preferredTranslationKey = repository.preferredTranslation(providerId, releaseId),
                            preferredQuality = repository.preferredQuality(providerId, releaseId),
                        )
                }.getOrElse { error ->
                    if (localPlayback == null) throw error
                    emptyList()
                }
                if (streams.isEmpty() && localPlayback == null) {
                    throw IllegalStateException("Источник не вернул доступных потоков")
                }
                val onlineProgress = repository.episodeProgress(providerId, episode.id)
                val effectiveProgress = PlaybackProgressMerger.choose(
                    primary = onlineProgress.toPlaybackProgressSnapshot(),
                    secondary = localPlayback?.progress,
                ).toOnlineWatchProgress()
                val episodeProgress = repository.progressFor(providerId).toMutableMap().apply {
                    put(episode.id, effectiveProgress)
                }
                OnlinePlaybackBundle(
                    providerId = providerId,
                    providerName = providerName,
                    releaseId = releaseId,
                    releaseName = release.name,
                    episode = episode.copy(streams = streams),
                    episodes = playable,
                    progress = effectiveProgress,
                    episodeProgress = episodeProgress,
                    nextEpisodeId = playable.getOrNull(index + 1)?.id,
                    localVariants = listOfNotNull(localPlayback?.variant),
                    localProgress = localPlayback?.progress,
                )
            }.fold(
                onSuccess = OnlinePlayerUiState::Ready,
                onFailure = { OnlinePlayerUiState.Error(it.toNetworkMessage(providerName)) },
            )
        }
    }

    fun onPlaybackSessionEvent(event: PlaybackSessionEvent) {
        playbackSessionStore.dispatch(event)
    }

    fun saveProgress(positionMs: Long, durationMs: Long, ended: Boolean = false) {
        val playback = (_uiState.value as? OnlinePlayerUiState.Ready)?.playback
        val release = loadedRelease
        val saved = if (playback != null && release != null) {
            repository.recordPlayback(
                release = release,
                episode = playback.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                ended = ended,
            )
        } else {
            repository.saveProgress(
                providerId = providerId,
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                ended = ended,
            )
        }

        _uiState.update { state ->
            val ready = state as? OnlinePlayerUiState.Ready ?: return@update state
            ready.copy(
                playback = ready.playback.copy(
                    progress = saved,
                    episodeProgress = ready.playback.episodeProgress + (episodeId to saved),
                ),
            )
        }
        playback
            ?.localVariants
            ?.firstOrNull()
            ?.localEpisodeId
            ?.let { localEpisodeId ->
                // Offline and online are two transports for the same linked episode, so keep one
                // logical progress value regardless of which transport is currently selected.
                viewModelScope.launch {
                    libraryRepository.savePlaybackProgress(
                        episodeId = localEpisodeId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        ended = ended,
                    )
                }
            }
        if (saved.isCompleted && !syncedCompletion && release != null) {
            syncedCompletion = true
            viewModelScope.launch {
                runCatchingCancellable {
                    val anilistId = aniListSyncRepository.resolveAniListId(
                        anilistId = release.externalIds.anilistId,
                        malId = release.externalIds.malId,
                    ) ?: return@runCatchingCancellable
                    val watched = playback?.episode?.ordinal?.toInt()?.takeIf { it > 0 } ?: return@runCatchingCancellable
                    aniListSyncRepository.syncEpisodeProgress(
                        anilistId = anilistId,
                        watchedEpisode = watched,
                        episodeCount = release.episodeCount,
                        forceCompleted = release.episodeCount?.let { watched >= it } == true,
                    )
                }
            }
        }
    }

    fun selectStream(stream: OnlineStream) {
        repository.setPreferredTranslation(
            providerId = providerId,
            releaseId = releaseId,
            translationKey = stream.translationPreferenceKey,
        )
        repository.setPreferredQuality(
            providerId = providerId,
            releaseId = releaseId,
            quality = stream.quality,
        )
    }

    class Factory(
        private val providerId: String,
        private val releaseId: String,
        private val episodeId: String,
        private val repository: OnlineRepository,
        private val libraryRepository: LibraryRepository,
        private val aniListSyncRepository: AniListSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnlinePlayerViewModel(
                providerId,
                releaseId,
                episodeId,
                repository,
                libraryRepository,
                aniListSyncRepository,
            ) as T
    }
}
