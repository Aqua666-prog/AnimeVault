package com.sergey.animevault.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.repository.PlaybackBundle
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
) : ViewModel() {
    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = repository.getPlaybackBundle(episodeId)
                ?.let(PlayerUiState::Ready)
                ?: PlayerUiState.Error("Серия не найдена или файл удалён")
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long, ended: Boolean = false) {
        viewModelScope.launch {
            repository.savePlaybackProgress(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                ended = ended,
            )
        }
    }

    class Factory(
        private val episodeId: Long,
        private val repository: LibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(episodeId, repository) as T
    }
}
