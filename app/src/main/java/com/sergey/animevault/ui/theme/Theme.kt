package com.sergey.animevault.ui.theme

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sergey.animevault.ui.preferences.isRaiden
import com.sergey.animevault.ui.preferences.allowDecorativeMotion
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.GraphicsPreset
import com.sergey.animevault.ui.preferences.effectivePreset
import com.sergey.animevault.ui.preferences.motionMode
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import kotlinx.coroutines.awaitCancellation
import kotlin.math.roundToInt

data class VaultVisualSettings(
    val theme: VaultThemeMode = VaultThemeMode.VAULT,
    val blurEnabled: Boolean = false,
    val cinematicEffects: Boolean = false,
    val powerSaveMode: Boolean = false,
    val effectivePreset: GraphicsPreset = GraphicsPreset.BALANCED,
    val motion: VaultMotionMode = VaultMotionMode.FULL,
    val dynamicArtwork: Boolean = false,
    val decorativeAnimations: Boolean = true,
)

val LocalVaultVisualSettings = compositionLocalOf { VaultVisualSettings() }

@Composable
fun vaultMotionDuration(baseMillis: Int): Int =
    (baseMillis * LocalVaultVisualSettings.current.motion.durationScale *
        if (LocalVaultVisualSettings.current.theme == VaultThemeMode.EUTHYMIA) 1.25f else 1f).roundToInt().coerceAtLeast(0)

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
private fun systemPowerSaveMode(): Boolean {
    val context = LocalContext.current.applicationContext
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var enabled by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enabled = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enabled = power?.isPowerSaveMode == true
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

@Composable
fun AnimeVaultTheme(
    settings: AppearanceSettings = AppearanceSettings(),
    graphicsPreset: GraphicsPreset = GraphicsPreset.BALANCED,
    content: @Composable () -> Unit,
) {
    val systemMotion = systemAnimationsEnabled()
    val context = LocalContext.current
    val powerSave = systemPowerSaveMode()
    val preset = graphicsPreset.effectivePreset(powerSave)
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    val cinematic = preset == GraphicsPreset.CINEMATIC_ULTRA && !lowRam
    val colors = if (settings.theme == VaultThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        // Dynamic surfaces/navigation retain Vault's semantic action colour and contrasting ink.
        androidx.compose.material3.dynamicDarkColorScheme(context).copy(
            secondary = VaultLemon, onSecondary = VaultInk,
            secondaryContainer = VaultAquaContainer, onSecondaryContainer = VaultLemonHighlight,
            surfaceTint = Color.Transparent,
        )
    } else remember(settings.theme) { vaultStaticColorScheme(settings.theme) }
    val raiden = settings.theme.isRaiden
    CompositionLocalProvider(
        LocalRaiden provides raidenTokens(settings.theme),
        LocalVaultColors provides VaultColors(
            action = if (raiden) colors.secondary else VaultLemon,
            actionPressed = if (raiden) colors.secondary.copy(alpha = .85f) else VaultLemonPressed,
            onAction = if (raiden) colors.onSecondary else VaultInk,
            highlight = if (raiden) colors.onSecondaryContainer else VaultLemonHighlight,
            card = colors.surfaceContainer,
            elevated = colors.surfaceContainerHigh, inactive = colors.onSurfaceVariant,
            navigation = colors.primary),
        LocalVaultVisualSettings provides VaultVisualSettings(
            theme = settings.theme,
            blurEnabled = preset != GraphicsPreset.PERFORMANCE && !lowRam && Build.VERSION.SDK_INT >= 31,
            cinematicEffects = cinematic,
            powerSaveMode = powerSave,
            effectivePreset = preset,
            motion = preset.motionMode(settings, systemMotion),
            dynamicArtwork = !lowRam && (cinematic || (preset == GraphicsPreset.BALANCED && settings.dynamicArtwork)),
            decorativeAnimations = preset != GraphicsPreset.PERFORMANCE && settings.allowDecorativeMotion(systemMotion),
        ),
    ) {
        MaterialTheme(colorScheme = colors, typography = if (raiden) RaidenTypography else AnimeVaultTypography, shapes = if (raiden) RaidenShapes else AnimeVaultShapes, content = content)
    }
}
