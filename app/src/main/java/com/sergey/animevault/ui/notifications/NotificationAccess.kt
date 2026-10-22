package com.sergey.animevault.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class NotificationAccess(val enabled: Boolean, val request: () -> Unit)

/**
 * The Activity hosts the only permission launcher and settings dialog.
 * Descendant screens reuse this handler, including its observable enabled state.
 */
internal val LocalNotificationAccess = compositionLocalOf<NotificationAccess?> { null }

private const val PREFS_NAME = "notification-permission"
private const val KEY_REQUESTED = "requested" // Suppresses repeat *automatic* prompts only.
private const val KEY_DENIED_ONCE = "confirmed_denial"

@Composable
internal fun rememberNotificationAccess(autoRequest: Boolean = false): NotificationAccess {
    LocalNotificationAccess.current?.let { return it }

    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var enabled by remember(context) {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var showSettings by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val rationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        val deniedBefore = preferences.getBoolean(KEY_DENIED_ONCE, false)
        val deniedNow = if (enabled) false else confirmedDenialAfterResult(deniedBefore, granted, rationale)
        preferences.edit { putBoolean(KEY_DENIED_ONCE, deniedNow) }
        // A first refusal (or a dismissed dialog) must not open a second dialog.
    }

    val request: () -> Unit = {
        val currentlyEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        enabled = currentlyEnabled
        if (currentlyEnabled) {
            showSettings = false
        } else {
            val requiresRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val runtimeGranted = !requiresRuntimePermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val rationale = requiresRuntimePermission && activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

            // Migrate an actual first denial from earlier releases that only persisted "requested".
            val deniedBefore = preferences.getBoolean(KEY_DENIED_ONCE, false)
            val confirmedDenial = deniedBefore || rationale
            if (confirmedDenial != deniedBefore) {
                preferences.edit { putBoolean(KEY_DENIED_ONCE, confirmedDenial) }
            }

            when (notificationPermissionAction(
                notificationsEnabled = currentlyEnabled,
                runtimePermissionRequired = requiresRuntimePermission,
                runtimePermissionGranted = runtimeGranted,
                hasConfirmedDenial = confirmedDenial,
                shouldShowRationale = rationale,
            )) {
                NotificationPermissionAction.NONE -> Unit
                NotificationPermissionAction.REQUEST_SYSTEM -> {
                    if (activity != null) {
                        preferences.edit { putBoolean(KEY_REQUESTED, true) }
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                NotificationPermissionAction.OPEN_SETTINGS -> showSettings = true
            }
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (enabled) {
                    preferences.edit { putBoolean(KEY_DENIED_ONCE, false) }
                    showSettings = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(autoRequest) {
        if (autoRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !preferences.getBoolean(KEY_REQUESTED, false)
        ) {
            request()
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Уведомления отключены") },
            text = { Text("Без разрешения AnimeVault не сможет показывать уведомления о загрузках и новых сериях. Вы можете разрешить их в настройках телефона или продолжить без уведомлений.") },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                    context.startActivity(intent)
                }) { Text("Открыть настройки") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Не сейчас") }
            },
        )
    }

    return NotificationAccess(enabled, request)
}
