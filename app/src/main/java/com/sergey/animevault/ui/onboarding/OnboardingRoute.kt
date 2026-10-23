package com.sergey.animevault.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.data.auth.GoogleCredentialClient
import com.sergey.animevault.ui.settings.AccountViewModel
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    accountViewModel: AccountViewModel,
    requestIdToken: (suspend () -> String)? = null,
    autoAdvance: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by accountViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val client = remember(context.applicationContext) {
        GoogleCredentialClient(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var authJob by remember { mutableStateOf<Job?>(null) }
    val motionScale = LocalVaultVisualSettings.current.motion.durationScale
    val accessibility = LocalAccessibilityManager.current

    DisposableEffect(state.step) {
        onDispose { authJob?.cancel() }
    }

    LaunchedEffect(state.gate, state.step, account.user) {
        if (state.gate == LaunchGate.ONBOARDING && state.step == OnboardingStep.ACCOUNT) {
            account.user?.let(viewModel::signedIn)
        }
    }

    LaunchedEffect(state.gate, state.step, autoAdvance) {
        if (autoAdvance && state.gate == LaunchGate.ONBOARDING && state.step == OnboardingStep.FINISH) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 1_600L,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: 1_600L
            delay(timeout)
            viewModel.finish()
        }
    }

    val back = {
        authJob?.cancel()
        viewModel.back()
    }
    BackHandler(enabled = state.gate == LaunchGate.ONBOARDING && state.step != OnboardingStep.WELCOME) {
        back()
    }

    OnboardingScreen(
        state = state,
        account = account,
        motionScale = motionScale,
        onStart = viewModel::start,
        onGoogle = {
            if (state.gate == LaunchGate.ONBOARDING && state.step == OnboardingStep.ACCOUNT &&
                authJob?.isActive != true) {
                authJob = scope.launch {
                    accountViewModel.signIn {
                        requestIdToken?.invoke() ?: client.getIdToken(checkNotNull(activity))
                    }
                }
            }
        },
        onGuest = {
            authJob?.cancel()
            viewModel.guest()
        },
        onBack = back,
        onFinish = viewModel::finish,
        onRetry = viewModel::load,
    )
}
