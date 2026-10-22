package com.sergey.animevault.ui.settings

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseNetworkException
import com.sergey.animevault.data.auth.AccountRepository
import com.sergey.animevault.data.auth.AccountUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = AccountUser("uid", "Test User", "test@example.com", null)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: FakeAccountRepository): AccountViewModel =
        AccountViewModel(repository).also { store.put("account", it) }

    @Test fun restoredSessionIsVisibleImmediatelyAndTracksExternalChanges() = runTest(dispatcher) {
        val repository = FakeAccountRepository(initial = account)
        val vm = viewModel(repository)
        assertThat(vm.state.value.user).isEqualTo(account)
        repository.user.value = null
        advanceUntilIdle()
        assertThat(vm.state.value.user).isNull()
    }

    @Test fun tokenIsExchangedAndUserAppears() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val vm = viewModel(repository)
        vm.signIn { "google-id-token" }
        advanceUntilIdle()
        assertThat(repository.receivedToken).isEqualTo("google-id-token")
        assertThat(vm.state.value.user).isEqualTo(account)
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.isError).isFalse()
    }

    @Test fun userCancellationKeepsGuestModeAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val vm = viewModel(repository)
        vm.signIn { throw GetCredentialCancellationException() }
        advanceUntilIdle()
        assertThat(repository.receivedToken).isNull()
        assertThat(vm.state.value.user).isNull()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.isError).isFalse()
        vm.signIn { "retry-token" }
        advanceUntilIdle()
        assertThat(vm.state.value.user).isEqualTo(account)
    }

    @Test fun missingConfigurationDoesNotRequestCredentials() = runTest(dispatcher) {
        val vm = viewModel(FakeAccountRepository(configured = false))
        vm.signIn { error("Credential UI must not be launched") }
        advanceUntilIdle()
        assertThat(vm.state.value.configured).isFalse()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.isError).isTrue()
    }

    @Test fun networkFailureShowsActionableMessageAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        repository.failure = FirebaseNetworkException("offline")
        val vm = viewModel(repository)
        vm.signIn { "token" }
        advanceUntilIdle()
        assertThat(vm.state.value.message).contains("интернет")
        assertThat(vm.state.value.isError).isTrue()
        assertThat(vm.state.value.busy).isFalse()
        repository.failure = null
        vm.signIn { "retry" }
        advanceUntilIdle()
        assertThat(vm.state.value.user).isEqualTo(account)
    }

    @Test fun signOutEndsSessionEvenWhenCredentialProviderFails() = runTest(dispatcher) {
        val repository = FakeAccountRepository(initial = account)
        val vm = viewModel(repository)
        vm.signOut {
            assertThat(repository.user.value).isNull()
            error("provider unavailable")
        }
        advanceUntilIdle()
        assertThat(repository.signOutCalls).isEqualTo(1)
        assertThat(vm.state.value.user).isNull()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.message).contains("Вы вышли")
    }

    @Test fun concurrentSignInIsIgnoredAndLifecycleCancellationReleasesBusyState() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val vm = viewModel(repository)
        val token = CompletableDeferred<String>()
        val first = async { vm.signIn { token.await() } }
        runCurrent()
        vm.signIn { error("Duplicate request") }
        first.cancel()
        try { first.await() } catch (_: CancellationException) { /* expected */ }
        advanceUntilIdle()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(repository.receivedToken).isNull()
        vm.signIn { "next" }
        advanceUntilIdle()
        assertThat(repository.receivedToken).isEqualTo("next")
    }

    private class FakeAccountRepository(
        override val configured: Boolean = true,
        initial: AccountUser? = null,
    ) : AccountRepository {
        override val user = MutableStateFlow(initial)
        var receivedToken: String? = null
        var signOutCalls = 0
        var failure: Exception? = null
        override suspend fun signIn(idToken: String) {
            failure?.let { throw it }
            receivedToken = idToken
            user.value = AccountUser("uid", "Test User", "test@example.com", null)
        }
        override fun signOut() {
            signOutCalls++
            user.value = null
        }
    }
}
