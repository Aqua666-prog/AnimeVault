package com.sergey.animevault.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.auth.AccountUser
import com.sergey.animevault.data.onboarding.OnboardingStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }

    private fun model(store: FakeStore, handle: SavedStateHandle = SavedStateHandle()): OnboardingViewModel =
        OnboardingViewModel(store, handle).also { models.put("onboarding", it) }

    @Test fun firstLaunchGuestFlowPersistsBeforeOpeningHome() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = model(store)
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.LOADING)
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        vm.start()
        vm.guest()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.FINISH)
        assertThat(vm.state.value.name).isNull()
        assertThat(store.completions).isEqualTo(0)
        val write = CompletableDeferred<Unit>()
        store.write = write
        vm.finish()
        runCurrent()
        assertThat(vm.state.value.saving).isTrue()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.ONBOARDING)
        vm.finish()
        write.complete(Unit)
        advanceUntilIdle()
        assertThat(store.completions).isEqualTo(1)
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.READY)
    }

    @Test fun googleSuccessShowsShortPersonalGreeting() = runTest(dispatcher) {
        val vm = model(FakeStore())
        advanceUntilIdle()
        vm.start()
        vm.signedIn(AccountUser("uid", "  Акира   Сато  ", "akira@example.com", null))
        assertThat(vm.state.value.name).isEqualTo("Акира")
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.FINISH)
    }

    @Test fun unnamedAccountHasNeutralGreeting() = runTest(dispatcher) {
        val vm = model(FakeStore())
        advanceUntilIdle()
        vm.start()
        vm.signedIn(AccountUser("uid", null, null, null))
        assertThat(vm.state.value.name).isNull()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.FINISH)
    }

    @Test fun backFromAccountReturnsToWelcomeAndBackFromFinishCompletes() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = model(store)
        advanceUntilIdle()
        vm.start()
        vm.back()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(store.completions).isEqualTo(0)
        vm.start()
        vm.guest()
        vm.back()
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.READY)
        assertThat(store.completions).isEqualTo(1)
    }

    @Test fun existingUserAndCompletedUserGoStraightToHome() = runTest(dispatcher) {
        val store = FakeStore(show = false)
        val vm = model(store)
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.READY)
        vm.start()
        vm.guest()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.READY)
        assertThat(store.completions).isEqualTo(0)
    }

    @Test fun accountStepSurvivesSavedStateRecreation() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(FakeStore(), saved)
        advanceUntilIdle()
        vm.start()
        val restored = model(FakeStore(), SavedStateHandle(mapOf(
            "onboarding_step" to saved.get<String>("onboarding_step"),
        )))
        advanceUntilIdle()
        assertThat(restored.state.value.step).isEqualTo(OnboardingStep.ACCOUNT)
    }

    @Test fun persistenceFailureCanBeRetriedWithoutLosingGreeting() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = model(store)
        advanceUntilIdle()
        vm.start()
        vm.signedIn(AccountUser("uid", "Акира", null, null))
        store.failWrite = true
        vm.finish()
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.ONBOARDING)
        assertThat(vm.state.value.saving).isFalse()
        assertThat(vm.state.value.error).isNotNull()
        assertThat(vm.state.value.name).isEqualTo("Акира")
        store.failWrite = false
        vm.finish()
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.READY)
    }

    @Test fun loadFailureExposesRetryInsteadOfRemainingInLoading() = runTest(dispatcher) {
        val store = FakeStore(failRead = true)
        val vm = model(store)
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.ERROR)
        store.failRead = false
        vm.load()
        advanceUntilIdle()
        assertThat(vm.state.value.gate).isEqualTo(LaunchGate.ONBOARDING)
    }

    private class FakeStore(
        var show: Boolean = true,
        var failRead: Boolean = false,
        var failWrite: Boolean = false,
    ) : OnboardingStore {
        var completions = 0
        var write: CompletableDeferred<Unit>? = null
        override suspend fun shouldShow(): Boolean {
            if (failRead) throw IOException("test read")
            return show
        }
        override suspend fun complete() {
            if (failWrite) throw IOException("test write")
            write?.await()
            completions++
            show = false
        }
    }
}
