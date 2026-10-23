package com.sergey.animevault.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.sergey.animevault.data.auth.AccountUser
import com.sergey.animevault.data.onboarding.OnboardingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LaunchGate { LOADING, ONBOARDING, READY, ERROR }
enum class OnboardingStep { WELCOME, ACCOUNT, FINISH }

data class OnboardingUiState(
    val gate: LaunchGate = LaunchGate.LOADING,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val name: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(
    private val store: OnboardingStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        OnboardingUiState(
            step = savedState.get<String>("onboarding_step")
                ?.let { value -> OnboardingStep.entries.firstOrNull { it.name == value } }
                ?: OnboardingStep.WELCOME,
            name = savedState["onboarding_name"],
        ),
    )
    val state = mutableState.asStateFlow()

    init { load() }

    fun load() {
        mutableState.value = mutableState.value.copy(gate = LaunchGate.LOADING, error = null)
        viewModelScope.launch {
            try {
                val show = store.shouldShow()
                mutableState.value = mutableState.value.copy(
                    gate = if (show) LaunchGate.ONBOARDING else LaunchGate.READY,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    gate = LaunchGate.ERROR,
                    error = "Не удалось прочитать настройки запуска. Попробуй ещё раз",
                )
            }
        }
    }

    fun start() {
        if (mutableState.value.gate == LaunchGate.ONBOARDING &&
            mutableState.value.step == OnboardingStep.WELCOME) moveTo(OnboardingStep.ACCOUNT)
    }

    fun guest() = welcome(null)

    fun signedIn(user: AccountUser) = welcome(
        user.name?.trim()?.takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.firstOrNull()?.take(48),
    )

    private fun welcome(name: String?) {
        if (mutableState.value.gate != LaunchGate.ONBOARDING ||
            mutableState.value.step != OnboardingStep.ACCOUNT) return
        savedState["onboarding_name"] = name
        mutableState.value = mutableState.value.copy(name = name, error = null)
        moveTo(OnboardingStep.FINISH)
    }

    fun back() {
        when (mutableState.value.step) {
            OnboardingStep.ACCOUNT -> moveTo(OnboardingStep.WELCOME)
            OnboardingStep.FINISH -> finish()
            OnboardingStep.WELCOME -> Unit // The Activity handles exit at the first page.
        }
    }

    fun finish() {
        val current = mutableState.value
        if (current.gate != LaunchGate.ONBOARDING ||
            current.step != OnboardingStep.FINISH || current.saving) return
        mutableState.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                store.complete() // Wait for durable persistence before entering the main NavHost.
                mutableState.value = mutableState.value.copy(gate = LaunchGate.READY, saving = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    saving = false,
                    error = "Не удалось сохранить настройки. Попробуй ещё раз",
                )
            }
        }
    }

    private fun moveTo(step: OnboardingStep) {
        savedState["onboarding_step"] = step.name
        mutableState.value = mutableState.value.copy(step = step, error = null)
    }

    class Factory(private val store: OnboardingStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            OnboardingViewModel(store, extras.createSavedStateHandle()) as T
    }
}
