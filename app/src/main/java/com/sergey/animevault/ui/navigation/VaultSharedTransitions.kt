@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.sergey.animevault.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings

data class VaultSharedPosterKey(
    val source: String,
    val id: String,
)

val LocalVaultSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalVaultAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.vaultSharedPoster(key: VaultSharedPosterKey?): Modifier {
    if (key == null || LocalVaultVisualSettings.current.motion == VaultMotionMode.MINIMAL) return this
    val shared = LocalVaultSharedTransitionScope.current ?: return this
    val animated = LocalVaultAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@vaultSharedPoster.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animated,
        )
    }
}
