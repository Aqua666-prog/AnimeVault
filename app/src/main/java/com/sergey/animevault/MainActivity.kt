package com.sergey.animevault

import android.content.Intent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sergey.animevault.ui.onboarding.LaunchGate
import com.sergey.animevault.ui.onboarding.OnboardingRoute
import com.sergey.animevault.ui.onboarding.OnboardingViewModel
import com.sergey.animevault.ui.settings.AccountViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.ui.notifications.LocalNotificationAccess
import com.sergey.animevault.ui.notifications.rememberNotificationAccess
import com.sergey.animevault.ui.navigation.AnimeVaultApp
import com.sergey.animevault.ui.player.enterPlayerPictureInPicture
import com.sergey.animevault.ui.startup.AnimeVaultLaunchIntro
import com.sergey.animevault.ui.startup.claimVaultReveal
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isPlayerInPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleAniListIntent(intent)
        setContent {
            val container = (application as AnimeVaultApplication).container
            val appearance by container.uiPreferences.appearance.collectAsStateWithLifecycle()
            val onboardingFactory = remember(container) { OnboardingViewModel.Factory(container.onboardingStore) }
            val onboarding: OnboardingViewModel = viewModel(factory = onboardingFactory)
            val firstLaunch by onboarding.state.collectAsStateWithLifecycle()
            val shouldAnimate = remember {
                claimVaultReveal(launcherLaunch = intent?.action == Intent.ACTION_MAIN, restoredState = savedInstanceState != null)
            }
            var showIntro by remember { mutableStateOf(shouldAnimate) }
            LaunchedEffect(firstLaunch.gate) {
                if (firstLaunch.gate == LaunchGate.ONBOARDING) showIntro = false
            }
            AnimeVaultTheme(settings = appearance) {
                val ready = firstLaunch.gate == LaunchGate.READY
                val notificationAccess = rememberNotificationAccess(autoRequest = ready && !showIntro)
                CompositionLocalProvider(LocalNotificationAccess provides notificationAccess) {
                    Box(Modifier.fillMaxSize()) {
                        if (ready) {
                            AnimeVaultApp(
                                isInPictureInPictureMode = isPlayerInPictureInPicture,
                                onEnterPictureInPicture = { enterPlayerPictureInPicture(this@MainActivity) },
                            )
                        } else {
                            val accountFactory = remember(container) {
                                AccountViewModel.Factory(container.accountRepository)
                            }
                            val account: AccountViewModel = viewModel(
                                key = "onboarding-account",
                                factory = accountFactory,
                            )
                            OnboardingRoute(onboarding, account)
                        }
                        if (ready && showIntro) {
                            AnimeVaultLaunchIntro(
                                motionScale = LocalVaultVisualSettings.current.motion.durationScale,
                                onFinished = { showIntro = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAniListIntent(intent)
    }

    private fun handleAniListIntent(intent: Intent?) {
        val repository = (application as? AnimeVaultApplication)?.container?.aniListSyncRepository ?: return
        if (!repository.handleOAuthRedirect(intent?.data)) return
        lifecycleScope.launch { repository.refreshViewer() }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPlayerInPictureInPicture = isInPictureInPictureMode
    }
}
