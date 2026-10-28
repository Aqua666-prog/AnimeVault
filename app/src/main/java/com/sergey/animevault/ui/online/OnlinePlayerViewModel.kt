package com.sergey.animevault.ui.online

import androidx.lifecycle.ViewModel
import com.sergey.animevault.data.anilist.AniListSyncRepository
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.online.prioritizePlaybackPreferences
import com.sergey.animevault.data.online.translationOptions
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
    val externalIds: ExternalAnimeIds = ExternalAnimeIds(),
    val localVariants: List<PlaybackVariant> = emptyList(),
    val localProgress: PlaybackProgressSnapshot? = null,
    val legacyProgressNotice: String? = null,
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
                progress = if (retargetedLocal.isNotEmpty() && localProgress != null) localProgress else onlinePlan.progress,
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

    private val discoveredStreams = java.util.concurrent.ConcurrentHashMap<String, OnlineStream>()
    private var loadedRelease: OnlineReleaseDetails? = null
    private var syncedCompletion = false
    private var prefetchedNextEpisodeId: String? = null

    init {
        viewModelScope.launch {
            val providerName = repository.descriptor(providerId).name
            _uiState.value = runCatchingCancellable {
                val baseRelease = repository.getRelease(providerId, releaseId)
                val preferredTranslation = repository.preferredTranslation(providerId, releaseId)
                val selectedTranslation = baseRelease.translationOptions()
                    .firstOrNull { it.matchesPreference(preferredTranslation) }
                val baseEpisode = baseRelease.episodes.firstOrNull { it.id == episodeId }
                val release = if (selectedTranslation != null) {
                    repository.getReleaseForTranslation(providerId, releaseId, selectedTranslation.key)
                } else {
                    baseRelease
                }
                if (selectedTranslation != null && baseEpisode != null) {
                    val sameEpisodeAvailable = release.episodes.any { candidate ->
                        candidate.id == episodeId ||
                            (baseEpisode.ordinal != null && candidate.ordinal != null &&
                                kotlin.math.abs(baseEpisode.ordinal - candidate.ordinal) < 0.0001)
                    }
                    if (!sameEpisodeAvailable) {
                        throw IllegalStateException("Эта серия пока недоступна в выбранной озвучке")
                    }
                }
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
                val resolution = runCatchingCancellable {
                    repository.resolveStreams(providerId, releaseId, episode)
                        .map { stream -> if (stream.episodeIdentity != null || providerId == com.sergey.animevault.data.online.OnlineProviderIds.UNIFIED) stream else
                            stream.copy(providerId = providerId, providerName = repository.descriptor(providerId).name,
                                episodeIdentity = com.sergey.animevault.data.online.EpisodeIdentityMatcher.from(release, episode)) }
                        .prioritizePlaybackPreferences(
                            preferredTranslationKey = repository.preferredTranslation(providerId, releaseId),
                            preferredQuality = repository.preferredQuality(providerId, releaseId),
                        )
                }
                var streams = resolution.getOrDefault(emptyList())
                val preferredProvider = repository.preferredPlaybackProvider(providerId, releaseId)
                if (preferredProvider != null && streams.none { it.providerId == preferredProvider }) {
                    val alternatives = runCatchingCancellable { repository.discoverEpisodeSources(release, episode) }.getOrDefault(emptyList())
                    streams = (streams + alternatives).prioritizePlaybackPreferences(
                        repository.preferredTranslation(providerId, releaseId), repository.preferredQuality(providerId, releaseId))
                        .sortedByDescending { it.providerId == preferredProvider }
                }
                if (streams.isEmpty() && localPlayback == null) {
                    throw resolution.exceptionOrNull() ?: IllegalStateException("Источник не вернул доступных потоков")
                }
                val originalIdentity = streams.firstOrNull()?.episodeIdentity
                val legacyProgress = repository.episodeProgress(providerId, episode.id)
                val sourceProgress = originalIdentity?.let { repository.episodeProgress(it.providerId, it.episodeId) }
                val onlineProgress = scopedOnlineResumeProgress(providerId, originalIdentity, legacyProgress, sourceProgress)
                val hasUnscopedHistory = legacyProgress.positionMs > 0 && onlineProgress.positionMs == 0L &&
                    originalIdentity != null && originalIdentity.providerId != providerId
                val effectiveProgress = localPlayback?.progress?.toOnlineWatchProgress() ?: onlineProgress
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
                    externalIds = release.externalIds,
                    localVariants = listOfNotNull(localPlayback?.variant),
                    localProgress = localPlayback?.progress,
                    legacyProgressNotice = if (hasUnscopedHistory) "Историческая позиция общей карточки: ${legacyProgress.positionMs / 60_000}:${((legacyProgress.positionMs / 1_000) % 60).toString().padStart(2, '0')}. Источник не установлен; восстановите вручную после проверки монтажа." else null,
                )
            }.fold(
                onSuccess = OnlinePlayerUiState::Ready,
                onFailure = { OnlinePlayerUiState.Error(it.toNetworkMessage(providerName)) },
            )
        }
    }

    suspend fun findNextSeason(): NextSeasonTarget? {
        val ready = _uiState.value as? OnlinePlayerUiState.Ready ?: return null
        if (ready.playback.nextEpisodeId != null) return null
        val release = loadedRelease ?: return null
        if (release.isOngoing) return null
        val playedOrdinal = ready.playback.episode.ordinal
        val expectedEpisodes = release.episodeCount
        if (expectedEpisodes != null && (playedOrdinal == null || playedOrdinal + 0.001 < expectedEpisodes)) return null
        return runCatchingCancellable {
            NextSeasonResolver(repository).find(release)
        }.getOrNull()
    }

    fun onPlaybackSessionEvent(event: PlaybackSessionEvent) {
        playbackSessionStore.dispatch(event)
    }

    suspend fun discoverSources(): List<OnlineStream> {
        val ready = (_uiState.value as? OnlinePlayerUiState.Ready) ?: return emptyList()
        val release = loadedRelease ?: return emptyList()
        val streams = repository.discoverEpisodeSources(release, ready.playback.episode)
        if (discoveredStreams.size > 64) discoveredStreams.clear()
        streams.forEach { stream -> discoveredStreams[com.sergey.animevault.data.playback.OnlineStreamVariantKeys.keyOf(stream)] = stream }
        return streams
    }

    /** Re-resolve an expiring CDN URL and return the fresh transport set to the player. */
    suspend fun refreshStreams(): List<OnlineStream>? {
        val state = _uiState.value as? OnlinePlayerUiState.Ready ?: return null
        val originalRelease = loadedRelease ?: return null
        val variantKey = playbackSessionStore.state.value.variantKey
        val operationGeneration = playbackSessionStore.state.value.sourceTransition.token
        val stream = state.playback.episode.streams.firstOrNull {
            com.sergey.animevault.data.playback.OnlineStreamVariantKeys.keyOf(it) == variantKey
        } ?: discoveredStreams[variantKey.orEmpty()]
        val identity = stream?.episodeIdentity
        val currentEpisode = state.playback.episode
        return runCatchingCancellable {
            val actualProvider = identity?.providerId ?: providerId
            val actualReleaseId = identity?.releaseId ?: releaseId
            val base = if (actualProvider == providerId && actualReleaseId == releaseId) originalRelease else repository.getRelease(actualProvider, actualReleaseId)
            val translation = stream?.translationPreferenceKey
            val release = if (translation != null && base.translationOptions().any { it.matchesPreference(translation) })
                repository.getReleaseForTranslation(actualProvider, actualReleaseId, translation) else base
            val sourceEpisode = release.episodes.firstOrNull { it.id == (identity?.episodeId ?: currentEpisode.id) }
                ?: return@runCatchingCancellable null

            val refreshed = repository.resolveStreams(
                providerId = actualProvider,
                releaseId = actualReleaseId,
                episode = sourceEpisode,
                forceRefresh = true,
            ).map { fresh -> if (fresh.episodeIdentity != null) fresh else fresh.copy(
                providerId = actualProvider, providerName = repository.descriptor(actualProvider).name,
                episodeIdentity = com.sergey.animevault.data.online.EpisodeIdentityMatcher.from(release, sourceEpisode)) }
                .prioritizePlaybackPreferences(
                    preferredTranslationKey = repository.preferredTranslation(providerId, releaseId),
                    preferredQuality = repository.preferredQuality(providerId, releaseId),
                )
            if (refreshed.isEmpty() || playbackSessionStore.state.value.sourceTransition.token != operationGeneration ||
                playbackSessionStore.state.value.variantKey != variantKey) return@runCatchingCancellable null
            val latest = _uiState.value as? OnlinePlayerUiState.Ready ?: return@runCatchingCancellable null
            _uiState.value = latest.copy(
                playback = latest.playback.copy(
                    episode = latest.playback.episode.copy(streams = refreshed + latest.playback.episode.streams.filter {
                        (it.episodeIdentity?.providerId ?: providerId) != actualProvider
                    }),
                ),
            )
            refreshed
        }.getOrNull()
    }

    /**
     * Warms provider resolution for the next episode shortly before autoplay. The repository keeps
     * the result only briefly because many providers return signed CDN URLs.
     */
    suspend fun prefetchNextEpisode(): Boolean {
        val state = _uiState.value as? OnlinePlayerUiState.Ready ?: return false
        val nextEpisodeId = state.playback.nextEpisodeId ?: return false
        if (prefetchedNextEpisodeId == nextEpisodeId) return true
        val release = loadedRelease ?: return false
        val nextEpisode = release.episodes.firstOrNull { it.id == nextEpisodeId }
            ?: state.playback.episodes.firstOrNull { it.id == nextEpisodeId }
            ?: return false

        return runCatchingCancellable {
            val streams = repository.resolveStreams(
                providerId = providerId,
                releaseId = releaseId,
                episode = nextEpisode,
                forceRefresh = false,
            ).prioritizePlaybackPreferences(
                preferredTranslationKey = repository.preferredTranslation(providerId, releaseId),
                preferredQuality = repository.preferredQuality(providerId, releaseId),
            )
            if (streams.isEmpty()) return@runCatchingCancellable false
            prefetchedNextEpisodeId = nextEpisodeId
            true
        }.getOrElse { false }
    }

    fun saveProgress(positionMs: Long, durationMs: Long, ended: Boolean = false) {
        val progressSession = playbackSessionStore.state.value
        val variantKey = progressSession.variantKey
        val activePlayback = (_uiState.value as? OnlinePlayerUiState.Ready)?.playback
        val identity = activePlayback?.playbackPlan?.variants?.firstOrNull { it.key == variantKey }?.episodeIdentity ?: discoveredStreams[variantKey.orEmpty()]?.episodeIdentity
        // Freeze source/episode before scheduling: a delayed coroutine must not read a new session.
        val playback = activePlayback
        val release = loadedRelease
        viewModelScope.launch {
            val saved = if (playback != null && release != null) {
                repository.recordPlayback(
                    release = if (identity == null) release else release.copy(providerId = identity.providerId,
                        providerName = repository.descriptor(identity.providerId).name, id = identity.releaseId),
                    episode = if (identity == null) playback.episode else playback.episode.copy(providerId = identity.providerId,
                        releaseId = identity.releaseId, id = identity.episodeId),
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
                if (playbackSessionStore.state.value.sourceTransition.token != progressSession.sourceTransition.token ||
                    playbackSessionStore.state.value.variantKey != variantKey ||
                    ready.playback.episode.id != playback?.episode?.id) return@update state
                ready.copy(
                    playback = ready.playback.copy(
                        progress = saved,
                        episodeProgress = ready.playback.episodeProgress + (episodeId to saved),
                    ),
                )
            }
            playback?.takeIf { identity == null || identity.providerId == providerId && identity.releaseId == releaseId }
                ?.localVariants
                ?.firstOrNull()
                ?.localEpisodeId
                ?.let { localEpisodeId ->
                    // Offline and online are two transports for the same linked episode, so keep one
                    // logical progress value regardless of which transport is currently selected.
                    libraryRepository.savePlaybackProgress(
                        episodeId = localEpisodeId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        ended = ended,
                    )
                }
            if (saved.isCompleted && !syncedCompletion && release != null) {
                syncedCompletion = true
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

    fun rememberQuality(quality: Int?) {
        repository.setPreferredQuality(providerId, releaseId, quality)
    }

    fun selectStream(stream: OnlineStream) {
        repository.setPreferredPlaybackProvider(providerId, releaseId, stream.providerId)
        repository.setPreferredTranslation(
            providerId = providerId,
            releaseId = releaseId,
            translationKey = stream.translationPreferenceKey,
        )
        stream.quality?.let { repository.setPreferredQuality(providerId, releaseId, it) }
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

/** Old group progress has no source proof. Keep it stored, never silently transfer it. */
internal fun scopedOnlineResumeProgress(provider: String,
    identity: com.sergey.animevault.data.online.EpisodeIdentity?, legacy: OnlineWatchProgress,
    source: OnlineWatchProgress?): OnlineWatchProgress = when {
    identity == null || identity.providerId == provider -> source?.takeIf { it.positionMs > 0 || it.isCompleted } ?: legacy
    else -> source ?: OnlineWatchProgress()
}
