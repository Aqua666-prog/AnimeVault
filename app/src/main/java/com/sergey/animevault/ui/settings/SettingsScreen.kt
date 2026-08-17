package com.sergey.animevault.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.BuildConfig
import com.sergey.animevault.data.db.LibraryFolderEntity
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.ProviderHealthState
import com.sergey.animevault.data.online.ProviderHealthStatus
import com.sergey.animevault.data.online.ProviderAccountState
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.components.VaultStatusPill
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val periodicScanEnabled by viewModel.periodicScanEnabled.collectAsStateWithLifecycle()
    val sourceHealth by viewModel.sourceHealth.collectAsStateWithLifecycle()
    SettingsScreen(
        folders = folders,
        onBack = onBack,
        onRescanFolder = viewModel::rescanFolder,
        onRemoveFolder = viewModel::removeFolder,
        onClearProgress = viewModel::clearProgress,
        periodicScanEnabled = periodicScanEnabled,
        onPeriodicScanChange = viewModel::setPeriodicScanEnabled,
        sourceProviders = viewModel.sourceProviders,
        sourceHealth = sourceHealth,
        onCheckOnlineSources = viewModel::checkOnlineSources,
        accounts = accounts.accounts,
        accountMessage = accounts.message,
        accountMessageIsError = accounts.isError,
        onSaveKodikToken = viewModel::saveKodikToken,
        onSaveAnimeLibToken = viewModel::saveAnimeLibToken,
        onSaveYummyToken = viewModel::saveYummyToken,
        onSignOut = viewModel::signOut,
        onConsumeAccountMessage = viewModel::consumeAccountMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    folders: List<LibraryFolderEntity>,
    onBack: () -> Unit,
    onRescanFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClearProgress: () -> Unit,
    periodicScanEnabled: Boolean,
    onPeriodicScanChange: (Boolean) -> Unit,
    sourceProviders: List<OnlineProviderDescriptor>,
    sourceHealth: Map<String, ProviderHealthState>,
    onCheckOnlineSources: () -> Unit,
    accounts: Map<String, ProviderAccountState>,
    accountMessage: String?,
    accountMessageIsError: Boolean,
    onSaveKodikToken: (String) -> Unit,
    onSaveAnimeLibToken: (String) -> Unit,
    onSaveYummyToken: (String) -> Unit,
    onSignOut: (String) -> Unit,
    onConsumeAccountMessage: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var confirmClearProgress by remember { mutableStateOf(false) }
    var showKodikToken by remember { mutableStateOf(false) }
    var showAnimeLibToken by remember { mutableStateOf(false) }
    var showYummyToken by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var mediaPermissionGranted by remember {
        mutableStateOf(context.hasGlobalVideoPermission())
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        mediaPermissionGranted = granted || context.hasGlobalVideoPermission()
    }

    LaunchedEffect(accountMessage) {
        if (accountMessage != null) {
            delay(5_000)
            onConsumeAccountMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = { AnimeBrandTitle("Настройки") },
                navigationIcon = {
                    VaultTopBarAction(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Папки видеотеки") }
            if (folders.isEmpty()) {
                item {
                    Text(
                        text = "Папки ещё не добавлены.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(folders, key = LibraryFolderEntity::treeUri) { folder ->
                    FolderItem(
                        folder = folder,
                        onRescan = { onRescanFolder(folder.treeUri) },
                        onRemove = { pendingRemoval = folder },
                    )
                }
            }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Фоновое пересканирование") },
                        supportingContent = {
                            Text("Раз в сутки, когда заряд и свободное место не на исходе")
                        },
                        leadingContent = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = periodicScanEnabled,
                                onCheckedChange = onPeriodicScanChange,
                            )
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Метаданные MediaStore") },
                        supportingContent = {
                            Text(
                                if (mediaPermissionGranted) {
                                    "Разрешение выдано: системный индекс может дополнять SAF длительностью и MIME"
                                } else {
                                    "Необязательно. SAF работает без него; доступ нужен только для системного индекса видео"
                                },
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.VideoLibrary,
                                contentDescription = null,
                                tint = if (mediaPermissionGranted) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingContent = if (mediaPermissionGranted) {
                            {
                                Text(
                                    "Включено",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        } else {
                            {
                                OutlinedButton(
                                    onClick = { mediaPermissionLauncher.launch(requiredMediaPermission()) },
                                ) {
                                    Text("Разрешить")
                                }
                            }
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }
            item { SectionTitle("Онлайн-источники") }
            item {
                SourceHealthCard(
                    providers = sourceProviders,
                    health = sourceHealth,
                    onCheck = onCheckOnlineSources,
                )
            }
            accountMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = if (accountMessageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
            item {
                AccountItem(
                    title = "Kodik",
                    description = "Работает с публичным токеном; собственный можно добавить как резервный",
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    account = accounts[OnlineProviderIds.KODIK],
                    onConnect = { showKodikToken = true },
                    onSignOut = { onSignOut(OnlineProviderIds.KODIK) },
                )
            }
            item {
                AccountItem(
                    title = "AnimeLib",
                    description = "Токен необязателен; без него остаются доступные внешние плееры",
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    account = accounts[OnlineProviderIds.ANIME_LIB],
                    onConnect = { showAnimeLibToken = true },
                    onSignOut = { onSignOut(OnlineProviderIds.ANIME_LIB) },
                )
            }
            item {
                AccountItem(
                    title = "YummyAnime",
                    description = "Токен необязателен; публичный каталог и озвучки работают без него",
                    icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    account = accounts[OnlineProviderIds.YUMMY],
                    onConnect = { showYummyToken = true },
                    onSignOut = { onSignOut(OnlineProviderIds.YUMMY) },
                )
            }
            item { SectionTitle("Данные") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("Сбросить прогресс просмотра") },
                        supportingContent = { Text("Файлы и каталог останутся без изменений") },
                        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                        trailingContent = {
                            OutlinedButton(onClick = { confirmClearProgress = true }) {
                                Text("Сбросить")
                            }
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }
            item { SectionTitle("О приложении") }
            item {
                SettingsCard {
                    ListItem(
                        headlineContent = { Text("AnimeVault ${BuildConfig.VERSION_NAME}") },
                        supportingContent = {
                            Text("Офлайн-видеотека, единый каталог, умный выбор потока, жесты плеера, эквалайзер и диагностика источников")
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    pendingRemoval?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Убрать папку?") },
            text = { Text("${folder.displayName} исчезнет из каталога. Сами видеофайлы не удаляются.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveFolder(folder.treeUri)
                        pendingRemoval = null
                    },
                ) { Text("Убрать") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Отмена") }
            },
        )
    }

    if (confirmClearProgress) {
        AlertDialog(
            onDismissRequest = { confirmClearProgress = false },
            title = { Text("Сбросить весь прогресс?") },
            text = { Text("Статусы и позиции воспроизведения будут удалены.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearProgress()
                        confirmClearProgress = false
                    },
                ) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearProgress = false }) { Text("Отмена") }
            },
        )
    }

    if (showKodikToken) {
        ApiTokenDialog(
            title = "API-токен Kodik",
            explanation = "Обычно AnimeVault получает публичный токен автоматически. Здесь можно сохранить собственный API-токен как более надёжный вариант; он будет зашифрован Android Keystore.",
            fieldLabel = "Kodik API-токен",
            onDismiss = { showKodikToken = false },
            onSave = { token ->
                onSaveKodikToken(token)
                showKodikToken = false
            },
        )
    }

    if (showAnimeLibToken) {
        ApiTokenDialog(
            title = "Токен AnimeLib",
            explanation = "Вставьте Bearer-токен из своего отдельного аккаунта AnimeLib. Он будет зашифрован Android Keystore.",
            fieldLabel = "Bearer-токен",
            onDismiss = { showAnimeLibToken = false },
            onSave = { token ->
                onSaveAnimeLibToken(token)
                showAnimeLibToken = false
            },
        )
    }

    if (showYummyToken) {
        ApiTokenDialog(
            title = "Application token YummyAnime",
            explanation = "Необязательно: можно добавить X-Application token для совместимости. Ключ хранится через Android Keystore и не вшивается в сборку.",
            fieldLabel = "X-Application token",
            onDismiss = { showYummyToken = false },
            onSave = { token ->
                onSaveYummyToken(token)
                showYummyToken = false
            },
        )
    }
}

@Composable
private fun SourceHealthCard(
    providers: List<OnlineProviderDescriptor>,
    health: Map<String, ProviderHealthState>,
    onCheck: () -> Unit,
) {
    val isChecking = health.values.any { it.status == ProviderHealthStatus.CHECKING }
    val availableCount = providers.count { health[it.id]?.status == ProviderHealthStatus.AVAILABLE }
    val unavailableCount = providers.count { health[it.id]?.status == ProviderHealthStatus.UNAVAILABLE }
    val needsConfigCount = providers.count { health[it.id]?.status == ProviderHealthStatus.NEEDS_CONFIGURATION }
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("Диагностика источников") },
                supportingContent = {
                    Text("Быстрая проверка каталога каждого провайдера без запуска видео")
                },
                leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                trailingContent = {
                    OutlinedButton(onClick = onCheck, enabled = !isChecking) {
                        Text(if (isChecking) "Проверяем…" else "Проверить")
                    }
                },
                colors = transparentListItemColors(),
            )
            if (isChecking) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 3.dp),
                )
            } else if (health.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    VaultStatusPill("Доступно $availableCount", accent = MaterialTheme.colorScheme.secondary)
                    if (unavailableCount > 0) {
                        VaultStatusPill("Ошибки $unavailableCount", accent = MaterialTheme.colorScheme.error)
                    }
                    if (needsConfigCount > 0) {
                        VaultStatusPill("Настройка $needsConfigCount", accent = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            providers.forEach { provider ->
                val state = health[provider.id] ?: ProviderHealthState(providerId = provider.id)
                val statusColor = when (state.status) {
                    ProviderHealthStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
                    ProviderHealthStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                    ProviderHealthStatus.NEEDS_CONFIGURATION -> MaterialTheme.colorScheme.tertiary
                    ProviderHealthStatus.CHECKING -> MaterialTheme.colorScheme.primary
                    ProviderHealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = when (state.status) {
                            ProviderHealthStatus.AVAILABLE -> Icons.Outlined.CheckCircle
                            ProviderHealthStatus.UNAVAILABLE -> Icons.Outlined.ErrorOutline
                            ProviderHealthStatus.CHECKING -> Icons.Outlined.HourglassTop
                            ProviderHealthStatus.NEEDS_CONFIGURATION -> Icons.Outlined.Key
                            ProviderHealthStatus.UNKNOWN -> Icons.Outlined.Tune
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = sourceHealthLabel(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun sourceHealthLabel(state: ProviderHealthState): String = when (state.status) {
    ProviderHealthStatus.UNKNOWN -> "Не проверен"
    ProviderHealthStatus.CHECKING -> "Проверка соединения"
    ProviderHealthStatus.AVAILABLE -> state.latencyMs?.let { "Работает · ${it} мс" } ?: "Работает"
    ProviderHealthStatus.NEEDS_CONFIGURATION -> state.message ?: "Нужна настройка"
    ProviderHealthStatus.UNAVAILABLE -> state.message?.take(90) ?: "Источник не ответил"
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
private fun transparentListItemColors() = androidx.compose.material3.ListItemDefaults.colors(
    containerColor = Color.Transparent,
)

@Composable
private fun AccountItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    account: ProviderAccountState?,
    onConnect: () -> Unit,
    onSignOut: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f),
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (account?.isSignedIn == true) {
                        "Подключено${account.displayName?.let { ": $it" }.orEmpty()}"
                    } else {
                        description
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account?.isSignedIn == true) {
                TextButton(onClick = onSignOut) { Text("Отключить") }
            } else {
                OutlinedButton(onClick = onConnect) { Text("Ключ") }
            }
        }
    }
}

@Composable
private fun ApiTokenDialog(
    title: String,
    explanation: String,
    fieldLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(explanation)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(fieldLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token) }, enabled = token.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun FolderItem(
    folder: LibraryFolderEntity,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = folder.lastScannedAt?.let {
                        "Сканирование: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
                    } ?: "Ещё не просканирована",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = folder.treeUri,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Пересканировать")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Убрать папку")
            }
        }
    }
}

private fun requiredMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun android.content.Context.hasGlobalVideoPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, requiredMediaPermission()) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
