package com.sergey.animevault.ui.theme

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import kotlinx.coroutines.awaitCancellation
import kotlin.math.roundToInt

data class VaultVisualSettings(
    val theme: VaultThemeMode = VaultThemeMode.VAULT,
    val blurEnabled: Boolean = false,
    val motion: VaultMotionMode = VaultMotionMode.FULL,
    val dynamicArtwork: Boolean = false,
)

val LocalVaultVisualSettings = staticCompositionLocalOf { VaultVisualSettings() }

@Composable
fun vaultMotionDuration(baseMillis: Int): Int =
    (baseMillis * LocalVaultVisualSettings.current.motion.durationScale).roundToInt().coerceAtLeast(0)

@Composable
fun vaultBlurEnabled(): Boolean = LocalVaultVisualSettings.current.blurEnabled

/** Re-read on resume, including after Android's animation setting changes. */
@Composable
private fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun readSystemSetting() = runCatching {
        (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
    var enabled by remember { mutableStateOf(readSystemSetting()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            enabled = readSystemSetting()
            awaitCancellation()
        }
    }
    return enabled
}

@Composable
fun AnimeVaultTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val systemMotion = systemAnimationsEnabled()
    val amoled = settings.theme == VaultThemeMode.OLED
    val background = if (amoled) Color.Black else VaultNight
    val card = if (amoled) VaultNightMiddle else VaultSurfaceHigh
    val colors = remember(amoled) {
        darkColorScheme(
            primary = VaultVioletBright,
            onPrimary = VaultInk,
            primaryContainer = VaultVioletContainer,
            onPrimaryContainer = VaultWhite,
            secondary = VaultLemon,
            onSecondary = VaultInk,
            secondaryContainer = VaultAquaContainer,
            onSecondaryContainer = VaultLemonHighlight,
            tertiary = VaultViolet,
            onTertiary = VaultWhite,
            tertiaryContainer = VaultVioletContainer,
            onTertiaryContainer = VaultWhite,
            background = background,
            onBackground = VaultWhite,
            surface = if (amoled) VaultNightMiddle else VaultSurface,
            onSurface = VaultWhite,
            surfaceVariant = card,
            onSurfaceVariant = VaultMuted,
            surfaceContainerLowest = background,
            surfaceContainerLow = if (amoled) VaultNightMiddle else VaultSurface,
            surfaceContainer = card,
            surfaceContainerHigh = VaultSurfaceHighest,
            surfaceContainerHighest = VaultSurfaceHighest,
            surfaceTint = Color.Transparent,
            outline = VaultOutline,
            outlineVariant = VaultOutlineSoft,
            scrim = Color.Black,
            error = VaultError,
            onError = VaultInk,
            errorContainer = Color(0xFF44202A),
            onErrorContainer = VaultWhite,
            inverseSurface = VaultWhite,
            inverseOnSurface = VaultNight,
        )
    }
    CompositionLocalProvider(
        LocalVaultColors provides VaultColors(card = card),
        LocalVaultVisualSettings provides VaultVisualSettings(
            theme = settings.theme,
            blurEnabled = settings.blurEnabled,
            motion = if (systemMotion) settings.effectiveMotion else VaultMotionMode.OFF,
            dynamicArtwork = settings.dynamicArtwork,
        ),
    ) {
        MaterialTheme(colorScheme = colors, typography = AnimeVaultTypography, shapes = AnimeVaultShapes, content = content)
    }
}
