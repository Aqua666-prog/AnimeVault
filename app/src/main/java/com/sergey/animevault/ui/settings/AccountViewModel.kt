package com.sergey.animevault.ui.settings

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.sergey.animevault.data.auth.AccountRepository
import com.sergey.animevault.data.auth.AccountUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AccountUiState(
    val user: AccountUser? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

private data class AccountOperation(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class AccountViewModel(private val repository: AccountRepository) : ViewModel() {
    private val operation = MutableStateFlow(AccountOperation())
    val state: StateFlow<AccountUiState> = combine(repository.user, operation) { user, op ->
        AccountUiState(user = user, busy = op.busy, message = op.message, isError = op.isError)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountUiState(user = repository.user.value),
    )

    // The UI coroutine owns Credential Manager's Activity and cancels it when the route leaves.
    suspend fun signIn(requestIdToken: suspend () -> String) {
        if (operation.value.busy) return
        operation.value = AccountOperation(busy = true)
        try {
            repository.signIn(requestIdToken())
            operation.value = AccountOperation(message = "Вход выполнен")
        } catch (_: GetCredentialCancellationException) {
            operation.value = AccountOperation(message = "Вход отменён. Можно продолжить без аккаунта")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            operation.value = AccountOperation(message = error.signInMessage(), isError = true)
        } finally {
            if (operation.value.busy) operation.value = AccountOperation()
        }
    }

    suspend fun signOut(clearCredentialState: suspend () -> Unit) {
        if (operation.value.busy) return
        operation.value = AccountOperation(busy = true)
        try {
            // Always terminate Firebase's session before asking the provider to clear its state.
            repository.signOut()
            clearCredentialState()
            operation.value = AccountOperation(message = "Вы вышли из аккаунта")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            operation.value = AccountOperation(
                message = if (repository.user.value == null)
                    "Вы вышли из аккаунта. Не удалось сбросить выбор Google-аккаунта; при следующем входе выберите его вручную"
                else "Не удалось выйти из аккаунта. Попробуйте ещё раз",
                isError = true,
            )
        } finally {
            if (operation.value.busy) operation.value = AccountOperation()
        }
    }

    class Factory(private val repository: AccountRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountViewModel(repository) as T
    }
}

private fun Exception.signInMessage(): String = when (this) {
    is NoCredentialException -> "Google-аккаунт не найден. Добавьте его в настройках устройства и повторите вход"
    is GetCredentialProviderConfigurationException -> "Вход через Google недоступен. Установите или обновите сервисы Google Play"
    is FirebaseNetworkException -> "Не удалось подключиться к Google. Проверьте интернет и повторите вход"
    is FirebaseTooManyRequestsException -> "Слишком много попыток входа. Попробуйте немного позже"
    is FirebaseAuthException -> when (errorCode) {
        "ERROR_USER_DISABLED" -> "Этот аккаунт отключён. Обратитесь в поддержку"
        "ERROR_INVALID_CREDENTIAL", "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED",
        "ERROR_INVALID_API_KEY" -> "Google не подтвердил вход. Обратитесь в поддержку приложения"
        else -> "Не удалось войти в аккаунт. Попробуйте ещё раз"
    }
    else -> "Не удалось войти через Google. Проверьте интернет и сервисы Google Play, затем повторите попытку"
}
