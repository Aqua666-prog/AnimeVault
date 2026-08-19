package com.sergey.animevault

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.ui.navigation.AnimeVaultApp
import com.sergey.animevault.ui.player.enterPlayerPictureInPicture
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var isPlayerInPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAniListIntent(intent)
        setContent {
            val appearance by (application as AnimeVaultApplication).container.uiPreferences.appearance.collectAsStateWithLifecycle()
            AnimeVaultTheme(settings = appearance) {
                AnimeVaultApp(
                    isInPictureInPictureMode = isPlayerInPictureInPicture,
                    onEnterPictureInPicture = { enterPlayerPictureInPicture(this) },
                )
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
