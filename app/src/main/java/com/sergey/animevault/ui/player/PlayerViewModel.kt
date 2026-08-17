package com.sergey.animevault.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.anilist.AniListSyncRepository
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.repository.PlaybackBundle
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(val playback: PlaybackBundle) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

class PlayerViewModel(
    private val episodeId: Long,
    private val repository: LibraryRepository,
    private val aniListSyncRepository: AniListSyncRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private var syncedCompletion = false

    init {
        viewModelScope.launch {
            _uiState.value = repository.getPlaybackBundle(episodeId)
                ?.let(PlayerUiState::Ready)
                ?: PlayerUiState.Error("Серия не найдена или файл удалён")
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long, ended: Boolean = false) {
        viewModelScope.launch {
            val completed = repository.savePlaybackProgress(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                ended = ended,
            )
            if (completed && !syncedCompletion) {
                syncedCompletion = true
                val target = repository.getAniListSyncTarget(episodeId) ?: return@launch
                runCatchingCancellable {
                    aniListSyncRepository.syncEpisodeProgress(
                        anilistId = target.anilistId,
                        watchedEpisode = target.watchedEpisode,
                        episodeCount = target.episodeCount,
                        forceCompleted = target.episodeCount != null && target.watchedEpisode >= target.episodeCount,
                    )
                }
            }
        }
    }

    class Factory(
        private val episodeId: Long,
        private val repository: LibraryRepository,
        private val aniListSyncRepository: AniListSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(episodeId, repository, aniListSyncRepository) as T
    }
}
