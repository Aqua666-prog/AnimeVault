package com.sergey.animevault.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Explicit player rotation that does not depend on the phone's auto-rotate
 * setting. The requested orientation belongs only to the current Activity.
 */
internal fun togglePlayerOrientation(context: Context): Boolean {
    val activity = context.findPlayerActivity() ?: return false
    activity.requestedOrientation = requestedPlayerOrientation(
        activity.resources.configuration.orientation,
    )
    return true
}

internal fun requestedPlayerOrientation(currentOrientation: Int): Int =
    if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
}

/**
 * The local player lives inside MainActivity, unlike the dedicated online PlayerActivity.
 * It therefore has to take ownership of system-bar visibility while it is on screen and
 * return that ownership when navigation leaves the player.
 */
@Composable
internal fun PlayerImmersiveEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findPlayerActivity() }

    SideEffect {
        activity?.setPlayerImmersiveMode(enabled)
    }
    DisposableEffect(activity, enabled) {
        onDispose {
            if (enabled && activity?.isInPictureInPictureMode != true) {
                activity?.setPlayerImmersiveMode(false)
            }
        }
    }
}

/** Restores MainActivity's previous orientation policy when the embedded player leaves composition. */
@Composable
internal fun PlayerOrientationEffect() {
    val context = LocalContext.current
    val activity = remember(context) { context.findPlayerActivity() }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        onDispose {
            if (activity != null && previousOrientation != null) {
                restorePlayerOrientation(activity, previousOrientation)
            }
        }
    }
}

internal fun restorePlayerOrientation(activity: Activity, previousOrientation: Int) {
    activity.requestedOrientation = previousOrientation
}

private fun Activity.setPlayerImmersiveMode(enabled: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            hide(WindowInsetsCompat.Type.systemBars())
        } else {
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal tailrec fun Context.findPlayerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPlayerActivity()
    else -> null
}
