package com.sergey.animevault.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.update.AppUpdate
import com.sergey.animevault.data.update.UpdateException
import com.sergey.animevault.data.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class UpdateOperation { IDLE, CHECKING, DOWNLOADING, VERIFYING }

data class UpdaterUiState(
    val automaticChecks: Boolean = true,
    val operation: UpdateOperation = UpdateOperation.IDLE,
    val update: AppUpdate? = null,
    val readyApk: File? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val showDialog: Boolean = false,
    val permissionRequired: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
) {
    val busy get() = operation != UpdateOperation.IDLE
}

class UpdaterViewModel(
    private val repository: UpdateRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdaterUiState(automaticChecks = repository.automaticChecks))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun setAutomaticChecks(enabled: Boolean) {
        repository.automaticChecks = enabled
        mutableState.update { it.copy(automaticChecks = enabled) }
    }

    fun checkAutomatically() {
        if (!repository.shouldCheckAutomatically(now())) return
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (state.value.busy) return
        repository.recordAutomaticCheck(now())
        mutableState.update { it.copy(operation = UpdateOperation.CHECKING, message = null, isError = false) }
        operation = viewModelScope.launch {
            try {
                val update = repository.latest()
                val cached = update?.let { repository.cachedApk(it) }
                mutableState.update {
                    it.copy(
                        operation = UpdateOperation.IDLE,
                        update = update,
                        readyApk = cached,
                        permissionRequired = false,
                        showDialog = update != null && (manual || !repository.isSkipped(update.versionCode)),
                        message = if (update == null) "Обновлений пока нет." else null,
                        isError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(operation = UpdateOperation.IDLE, message = errorMessage(error), isError = true)
                }
            }
        }
    }

    fun showDetails() {
        mutableState.update { it.copy(showDialog = true) }
    }

    fun dismiss() {
        mutableState.update { it.copy(showDialog = false) }
    }

    fun skipVersion() {
        state.value.update?.let { repository.skip(it.versionCode) }
        dismiss()
    }

    fun download() {
        val update = state.value.update ?: return
        if (state.value.busy) return
        mutableState.update {
            it.copy(operation = UpdateOperation.DOWNLOADING, readyApk = null, receivedBytes = 0,
                totalBytes = update.apk.size, message = null, isError = false, permissionRequired = false)
        }
        operation = viewModelScope.launch {
            try {
                val file = repository.download(update) { received, total ->
                    mutableState.update { it.copy(receivedBytes = received, totalBytes = total) }
                }
                mutableState.update {
                    it.copy(operation = UpdateOperation.IDLE, readyApk = file,
                        message = "APK проверен и готов к установке.", isError = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(operation = UpdateOperation.IDLE, message = errorMessage(error), isError = true)
                }
            }
        }
    }

    fun cancelDownload() {
        if (state.value.operation != UpdateOperation.DOWNLOADING) return
        operation?.cancel()
        // Block a new download until the old IO operation has closed and deleted its partial file.
        val previous = operation
        viewModelScope.launch {
            previous?.join()
            mutableState.update {
                it.copy(operation = UpdateOperation.IDLE, message = "Загрузка отменена.", isError = false)
            }
        }
    }

    fun requireInstallPermission(required: Boolean) {
        mutableState.update {
            it.copy(permissionRequired = required,
                message = if (required) "Разрешите установку из AnimeVault в настройках Android, затем нажмите «Установить»." else null,
                isError = false)
        }
    }

    fun prepareInstall(onReady: (File) -> Unit) {
        val update = state.value.update ?: return
        val file = state.value.readyApk ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(operation = UpdateOperation.VERIFYING, message = null, isError = false) }
        operation = viewModelScope.launch {
            try {
                repository.verify(file, update)
                mutableState.update { it.copy(operation = UpdateOperation.IDLE, permissionRequired = false) }
                onReady(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(operation = UpdateOperation.IDLE, readyApk = null, message = errorMessage(error), isError = true)
                }
            }
        }
    }

    fun installerOpened() {
        mutableState.update {
            it.copy(message = "Подтвердите обновление в системном установщике. Если вы отменили установку, её можно повторить.",
                isError = false)
        }
    }

    fun reportLaunchError() {
        mutableState.update {
            it.copy(message = "Не удалось открыть системный установщик или настройки установки на этом устройстве.", isError = true)
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is UpdateException -> error.message ?: "Обновление отклонено."
        is IOException -> "Не удалось связаться с GitHub или сохранить APK. Проверьте подключение и свободное место, затем повторите попытку."
        else -> "Не удалось прочитать обновление. Попробуйте позже."
    }

    class Factory(private val repository: UpdateRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UpdaterViewModel::class.java))
            return UpdaterViewModel(repository) as T
        }
    }
}
