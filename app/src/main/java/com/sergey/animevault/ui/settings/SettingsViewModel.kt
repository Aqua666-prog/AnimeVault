package com.sergey.animevault.ui.settings

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.db.LibraryFolderEntity
import com.sergey.animevault.data.anilist.AniListAccountState
import com.sergey.animevault.data.anilist.AniListSyncRepository
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.ProviderHealthState
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.ProviderAccountState
import com.sergey.animevault.data.repository.LibraryRepository
import com.sergey.animevault.data.repository.AnimeVaultBackupRepository
import com.sergey.animevault.data.repository.BackupRestoreResult
import com.sergey.animevault.data.repository.StorageCleanupResult
import com.sergey.animevault.data.repository.StorageSummary
import com.sergey.animevault.data.repository.summarizeStorage
import com.sergey.animevault.data.scanner.OfflineScanScheduler
import com.sergey.animevault.ui.online.toNetworkMessage
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    private val aniListSyncRepository: AniListSyncRepository,
    private val backupRepository: AnimeVaultBackupRepository,
) : ViewModel() {
    val periodicScanEnabled: StateFlow<Boolean> = offlineScanScheduler.enabled
    val sourceProviders: List<OnlineProviderDescriptor> = onlineRepository.healthProviders
    val sourceHealth: StateFlow<Map<String, ProviderHealthState>> = onlineRepository.healthStates
    val aniListState: StateFlow<AniListAccountState> = aniListSyncRepository.state
    val aniListClientId: String? get() = aniListSyncRepository.clientId
    val folders: StateFlow<List<LibraryFolderEntity>> = repository.observeFolders().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val storageSummary: StateFlow<StorageSummary> = repository.observeLibrary()
        .map(::summarizeStorage)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StorageSummary(),
        )
    private val _storageCleanup = MutableStateFlow<StorageCleanupResult?>(null)
    val storageCleanup: StateFlow<StorageCleanupResult?> = _storageCleanup.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    private val _accounts = MutableStateFlow(
        OnlineAccountsUiState(accounts = onlineRepository.accountStates.value),
    )
    val accounts: StateFlow<OnlineAccountsUiState> = _accounts.asStateFlow()


    init {
        if (aniListSyncRepository.isAuthenticated) {
            viewModelScope.launch { aniListSyncRepository.refreshViewer() }
        }
    }

    fun saveAniListClientId(clientId: String) {
        aniListSyncRepository.setClientId(clientId)
        _accounts.value = _accounts.value.copy(
            message = if (clientId.isBlank()) "AniList client ID удалён" else "AniList client ID сохранён",
            isError = false,
        )
    }

    fun aniListAuthorizationUrl(): String? = aniListSyncRepository.authorizationUri()?.toString()

    fun refreshAniList() {
        viewModelScope.launch { aniListSyncRepository.refreshViewer() }
    }

    fun signOutAniList() {
        aniListSyncRepository.signOut()
        _accounts.value = _accounts.value.copy(message = "AniList: сессия удалена", isError = false)
    }

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

    fun deleteCompletedFiles() {
        viewModelScope.launch {
            _storageCleanup.value = repository.deleteCompletedVideoFiles()
        }
    }

    fun consumeStorageCleanupResult() {
        _storageCleanup.value = null
    }

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatchingCancellable { backupRepository.exportTo(uri) }
                .onSuccess { backup ->
                    _backupMessage.value = "Резервная копия сохранена: ${backup.progress.size} позиций, ${backup.metadata.size} метаданных"
                }
                .onFailure { error -> _backupMessage.value = "Не удалось сохранить копию: ${error.message ?: "ошибка"}" }
        }
    }

    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatchingCancellable { backupRepository.importFrom(uri) }
                .onSuccess { result -> _backupMessage.value = result.toUserMessage() }
                .onFailure { error -> _backupMessage.value = "Не удалось восстановить копию: ${error.message ?: "ошибка"}" }
        }
    }

    fun consumeBackupMessage() {
        _backupMessage.value = null
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

    private fun BackupRestoreResult.toUserMessage(): String =
        "Восстановлено: локальный прогресс $progressRestored, метаданные $metadataRestored, связи $linksRestored, " +
            "группировка $groupingOverridesRestored, онлайн ${onlineLibraryRestored + onlineProgressRestored}" +
            if (skipped > 0) "; пропущено $skipped (сначала просканируйте соответствующие папки)" else ""

    class Factory(
        private val repository: LibraryRepository,
        private val onlineRepository: OnlineRepository,
        private val offlineScanScheduler: OfflineScanScheduler,
        private val aniListSyncRepository: AniListSyncRepository,
        private val backupRepository: AnimeVaultBackupRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, onlineRepository, offlineScanScheduler, aniListSyncRepository, backupRepository) as T
    }
}
