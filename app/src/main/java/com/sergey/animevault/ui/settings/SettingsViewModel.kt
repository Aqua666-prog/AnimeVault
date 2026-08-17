package com.sergey.animevault.ui.settings

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.db.LibraryFolderEntity
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.ProviderHealthState
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.ProviderAccountState
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.scanner.OfflineScanScheduler
import com.sergey.animevault.ui.online.toNetworkMessage
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class OnlineAccountsUiState(
    val accounts: Map<String, ProviderAccountState> = emptyMap(),
    val message: String? = null,
    val isError: Boolean = false,
)

class SettingsViewModel(
    private val repository: LibraryRepository,
    private val onlineRepository: OnlineRepository,
    private val offlineScanScheduler: OfflineScanScheduler,
) : ViewModel() {
    val periodicScanEnabled: StateFlow<Boolean> = offlineScanScheduler.enabled
    val sourceProviders: List<OnlineProviderDescriptor> = onlineRepository.healthProviders
    val sourceHealth: StateFlow<Map<String, ProviderHealthState>> = onlineRepository.healthStates
    val folders: StateFlow<List<LibraryFolderEntity>> = repository.observeFolders().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _accounts = MutableStateFlow(
        OnlineAccountsUiState(accounts = onlineRepository.accountStates.value),
    )
    val accounts: StateFlow<OnlineAccountsUiState> = _accounts.asStateFlow()

    fun removeFolder(treeUri: String) {
        viewModelScope.launch { repository.removeFolder(treeUri) }
    }

    fun rescanFolder(treeUri: String) {
        viewModelScope.launch { runCatchingCancellable { repository.scanFolder(treeUri.toUri()) } }
    }

    fun clearProgress() {
        onlineRepository.clearProgress()
        viewModelScope.launch { repository.clearProgress() }
    }

    fun setPeriodicScanEnabled(enabled: Boolean) {
        offlineScanScheduler.setEnabled(enabled)
    }

    fun checkOnlineSources() {
        viewModelScope.launch {
            runCatchingCancellable { onlineRepository.checkAllProviders() }
                .onFailure { error ->
                    _accounts.value = _accounts.value.copy(
                        message = error.toNetworkMessage("онлайн-источников"),
                        isError = true,
                    )
                }
        }
    }

    fun saveAnimeLibToken(token: String) {
        saveToken(
            providerId = OnlineProviderIds.ANIME_LIB,
            token = token,
            successMessage = "AnimeLib: токен сохранён в защищённом хранилище",
            sourceName = "AnimeLib",
        )
    }

    fun saveKodikToken(token: String) {
        saveToken(
            providerId = OnlineProviderIds.KODIK,
            token = token,
            successMessage = "Kodik: API-токен сохранён в защищённом хранилище",
            sourceName = "Kodik",
        )
    }

    fun saveYummyToken(token: String) {
        saveToken(
            providerId = OnlineProviderIds.YUMMY,
            token = token,
            successMessage = "YummyAnime: application token сохранён в защищённом хранилище",
            sourceName = "YummyAnime",
        )
    }

    private fun saveToken(providerId: String, token: String, successMessage: String, sourceName: String) {
        runCatching { onlineRepository.setToken(providerId, token) }
            .onSuccess {
                _accounts.value = OnlineAccountsUiState(
                    accounts = onlineRepository.accountStates.value,
                    message = successMessage,
                )
            }
            .onFailure { error ->
                _accounts.value = OnlineAccountsUiState(
                    accounts = onlineRepository.accountStates.value,
                    message = error.toNetworkMessage(sourceName),
                    isError = true,
                )
            }
    }

    fun signOut(providerId: String) {
        onlineRepository.signOut(providerId)
        _accounts.value = OnlineAccountsUiState(
            accounts = onlineRepository.accountStates.value,
            message = "Сессия удалена",
        )
    }

    fun consumeAccountMessage() {
        _accounts.value = _accounts.value.copy(message = null, isError = false)
    }

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
        private val offlineScanScheduler: OfflineScanScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, onlineRepository, offlineScanScheduler) as T
    }
}
