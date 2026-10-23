package com.sergey.animevault.ui.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.BuildConfig
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultSurfaceRole
import java.util.Locale

@Composable
fun UpdaterHost(
    viewModel: UpdaterViewModel,
    allowAutomaticCheck: Boolean,
    allowDialog: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.requireInstallPermission(
            Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls(),
        )
    }
    LaunchedEffect(allowAutomaticCheck) {
        if (allowAutomaticCheck) viewModel.checkAutomatically()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (allowAutomaticCheck) viewModel.checkAutomatically()
    }
    if (!allowDialog || !state.showDialog || state.update == null) return
    val update = state.update ?: return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("AnimeVault ${update.versionName}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Доступно обновление · ${update.versionCode}\nУстановлено: ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(update.release.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(update.release.notes.ifBlank { "Автор релиза не добавил описание изменений." })
                Text(
                    "APK: ${sizeLabel(update.apk.size)}. Обновление сохранит библиотеку, историю, загрузки и аккаунт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateProgress(state)
                UpdateMessage(state)
                if (state.operation == UpdateOperation.DOWNLOADING) {
                    Text("Загрузка выполняется в приложении. После его закрытия системой может потребоваться повторная загрузка.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::cancelDownload) { Text("Отменить загрузку") }
                }
                if (!state.busy) {
                    TextButton(onClick = viewModel::skipVersion) { Text("Не напоминать об этой версии") }
                }
            }
        },
        confirmButton = {
            when {
                state.busy -> TextButton(onClick = viewModel::dismiss) { Text("Свернуть") }
                state.readyApk == null -> Button(onClick = viewModel::download) { Text("Скачать APK") }
                state.permissionRequired && Build.VERSION.SDK_INT >= 26 -> Button(onClick = {
                    runCatching {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    }.onFailure { viewModel.reportLaunchError() }
                }) { Text("Разрешить установку") }
                else -> Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                        viewModel.requireInstallPermission(true)
                    } else {
                        viewModel.prepareInstall { file ->
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "application/vnd.android.package-archive")
                                        .apply { clipData = ClipData.newRawUri("AnimeVault update", uri) }
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                viewModel.installerOpened()
                            }.onFailure { viewModel.reportLaunchError() }
                        }
                    }
                }) { Text("Установить") }
            }
        },
        dismissButton = {
            if (!state.busy) TextButton(onClick = viewModel::dismiss) { Text("Позже") }
        },
    )
}

@Composable
fun UpdaterSettingsCard(viewModel: UpdaterViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VaultPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        role = VaultSurfaceRole.Card,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Обновления AnimeVault", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Версия ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}\nОбновления из официальных GitHub Releases.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Проверять автоматически раз в сутки", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.automaticChecks, onCheckedChange = viewModel::setAutomaticChecks)
            }
            UpdateProgress(state)
            UpdateMessage(state)
            if (state.update != null) {
                Button(onClick = viewModel::showDetails, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.readyApk != null) "Обновление готово к установке" else "Версия ${state.update?.versionName}: подробнее")
                }
            }
            OutlinedButton(
                onClick = { viewModel.check() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.operation == UpdateOperation.CHECKING) "Проверяем…" else "Проверить обновления")
            }
        }
    }
}

@Composable
private fun UpdateProgress(state: UpdaterUiState) {
    when (state.operation) {
        UpdateOperation.IDLE -> Unit
        UpdateOperation.CHECKING, UpdateOperation.VERIFYING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(if (state.operation == UpdateOperation.CHECKING) "Проверяем GitHub Releases…" else "Проверяем APK перед установкой…",
                style = MaterialTheme.typography.bodySmall)
        }
        UpdateOperation.DOWNLOADING -> {
            val progress = if (state.totalBytes > 0) (state.receivedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${sizeLabel(state.receivedBytes)} / ${sizeLabel(state.totalBytes)}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateMessage(state: UpdaterUiState) {
    state.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sizeLabel(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f МБ", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
