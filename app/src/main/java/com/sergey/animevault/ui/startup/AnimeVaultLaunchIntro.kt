package com.sergey.animevault.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultLogoMark
import kotlinx.coroutines.delay

/** Brief brand continuity. The launch gate continues to own initialization and onboarding. */
@Composable
fun AnimeVaultLaunchIntro(motionScale: Float, onFinished: () -> Unit) {
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        if (motionScale >= 1f) delay(180L)
        finished.value()
    }
    BackHandler { finished.value() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VaultLogoMark()
        Spacer(Modifier.height(16.dp))
        Text("AnimeVault", style = MaterialTheme.typography.headlineMedium)
    }
}
