package com.sergey.animevault.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Persists a lightweight playback checkpoint when the host Activity goes to background. */
@Composable
internal fun PlayerStopEffect(
    key: Any,
    onStop: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnStop = rememberUpdatedState(onStop)
    DisposableEffect(lifecycleOwner, key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestOnStop.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
