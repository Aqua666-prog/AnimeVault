package com.sergey.animevault

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sergey.animevault.ui.navigation.AnimeVaultApp
import com.sergey.animevault.ui.player.enterPlayerPictureInPicture
import com.sergey.animevault.ui.theme.AnimeVaultTheme

class MainActivity : ComponentActivity() {
    private var isPlayerInPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnimeVaultTheme {
                AnimeVaultApp(
                    isInPictureInPictureMode = isPlayerInPictureInPicture,
                    onEnterPictureInPicture = { enterPlayerPictureInPicture(this) },
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPlayerInPictureInPicture = isInPictureInPictureMode
    }
}
