package com.sergey.animevault.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class NotificationAccess(val enabled: Boolean, val request: () -> Unit)

/** Share the permission flow across startup, subscriptions and settings. */
@Composable
internal fun rememberNotificationAccess(autoRequest: Boolean = false): NotificationAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE)
    }
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var showSettings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) showSettings = true
    }
    val request: () -> Unit = {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) {
            if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                (!preferences.getBoolean("requested", false) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
                preferences.edit { putBoolean("requested", true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                showSettings = true
            }
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(autoRequest) {
        if (autoRequest && Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("requested", false)) {
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
                    val intent = if (Build.VERSION.SDK_INT >= 26) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))
                    }
                    context.startActivity(intent)
                }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Не сейчас") } },
        )
    }
    return NotificationAccess(enabled, request)
}
