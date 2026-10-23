package com.sergey.animevault.ui.onboarding

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.google.firebase.FirebaseNetworkException
import com.sergey.animevault.data.auth.AccountRepository
import com.sergey.animevault.data.auth.AccountUser
import com.sergey.animevault.data.onboarding.OnboardingStore
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.settings.AccountViewModel
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var onboarding: OnboardingViewModel
    private lateinit var account: AccountViewModel

    @After fun cleanup() {
        if (::onboarding.isInitialized) onboarding.viewModelScope.cancel()
        if (::account.isInitialized) account.viewModelScope.cancel()
    }

    private fun mount(
        store: MemoryStore = MemoryStore(),
        fontScale: Float = 1f,
        token: suspend () -> String = { "ui-test-token" },
    ): MemoryStore {
        onboarding = OnboardingViewModel(store, SavedStateHandle())
        account = AccountViewModel(MemoryAccount())
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }
        rule.setContent {
            val current by onboarding.state.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AnimeVaultTheme(AppearanceSettings(theme = VaultThemeMode.OLED, motion = VaultMotionMode.OFF)) {
                    if (current.gate == LaunchGate.READY) {
                        Text("Главный экран", modifier = Modifier.testTag("test-home"))
                    } else {
                        OnboardingRoute(onboarding, account, requestIdToken = token, autoAdvance = false)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { onboarding.state.value.gate != LaunchGate.LOADING }
        rule.waitForIdle()
        return store
    }

    private fun start() {
        rule.onNodeWithText("Начать").performScrollTo().performClick()
        rule.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "onboarding-preview")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, name + ".png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun guestFinishesAndRelaunchDoesNotShowWelcome() {
        val store = mount()
        screenshot("01-welcome")
        start()
        screenshot("02-account")
        rule.onNodeWithText("Продолжить как гость").performScrollTo().performClick()
        rule.onNodeWithText("Добро пожаловать!").assertExists()
        screenshot("03-finish-guest")
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
        assertTrue(store.completed)
        rule.runOnUiThread { onboarding.load() }
        rule.waitUntil(5_000) { onboarding.state.value.gate == LaunchGate.READY }
        rule.onNodeWithTag("test-home").assertExists()
        rule.onNodeWithText("Начать").assertDoesNotExist()
    }

    @Test fun cancelledGoogleStaysOnAccountAndRetryShowsPersonalGreeting() {
        var attempts = 0
        mount(token = {
            attempts++
            if (attempts == 1) throw GetCredentialCancellationException()
            "ui-test-token"
        })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Вход отменён. Можно продолжить без аккаунта")
            .performScrollTo().assertIsDisplayed()
        assertEquals(OnboardingStep.ACCOUNT, onboarding.state.value.step)
        rule.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Привет, Акира!").assertExists()
        screenshot("04-finish-google")
        assertEquals(2, attempts)
    }

    @Test fun networkErrorStillAllowsGuest() {
        mount(token = { throw FirebaseNetworkException("test offline") })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Не удалось подключиться к Google. Проверьте интернет и повторите вход")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Продолжить как гость").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
    }

    @Test fun guestRemainsAvailableDuringPendingGoogleRequest() {
        val pending = CompletableDeferred<String>()
        mount(token = { pending.await() })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Продолжить как гость").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Добро пожаловать!").assertExists()
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
    }

    @Test fun backAndLargeTextKeepActionsReachable() {
        mount(fontScale = 1.6f)
        start()
        screenshot("05-large-text")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Начать").performScrollTo().assertIsDisplayed()
        start()
        rule.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        rule.onNodeWithText("Начать").performScrollTo().assertIsDisplayed()
    }

    private class MemoryStore : OnboardingStore {
        var completed = false
        override suspend fun shouldShow() = !completed
        override suspend fun complete() { completed = true }
    }

    private class MemoryAccount : AccountRepository {
        override val user = MutableStateFlow<AccountUser?>(null)
        override suspend fun signIn(idToken: String) {
            check(idToken == "ui-test-token")
            user.value = AccountUser("ui-test-user", "Акира Сато", "test@example.com", null)
        }
        override fun signOut() { user.value = null }
    }
}
