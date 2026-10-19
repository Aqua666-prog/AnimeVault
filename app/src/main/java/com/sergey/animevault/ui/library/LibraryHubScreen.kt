package com.sergey.animevault.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.sergey.animevault.AnimeVaultApplication
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultFilterChip
import com.sergey.animevault.ui.components.VaultPosterCard
import com.sergey.animevault.ui.components.VaultScreenHeading
import com.sergey.animevault.ui.components.VaultSkeletonBlock
import com.sergey.animevault.ui.components.VaultTopBarAction
import com.sergey.animevault.ui.home.ContinueWatchingCard
import com.sergey.animevault.ui.home.HomeContinueItem
import com.sergey.animevault.ui.navigation.VaultSharedPosterKey
import com.sergey.animevault.ui.preferences.VaultTitleList

private enum class LibraryHubTab(val title: String) {
    CONTINUE("Продолжить"), FAVORITES("Избранное"), PLANNED("Буду смотреть"),
    WATCHING("Смотрю"), ON_HOLD("Отложено"), WATCHED("Просмотрено"),
    DROPPED("Брошено"), RATED("Оценённое"), LOCAL("На устройстве"), ALL("Все"),
}

@Composable
fun LibraryHubRoute(
    viewModel: LibraryHubViewModel,
    onOpenLocal: (Long) -> Unit,
    onOpenOnline: (String, String) -> Unit,
    onContinue: (HomeContinueItem) -> Unit,
    onOpenOfflineLibrary: () -> Unit,
    onOpenHistory: () -> Unit,
    onManageOnline: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var csvPreview by remember { mutableStateOf(false) }
    var subscriptionsDialog by remember { mutableStateOf(false) }
    val preferences = (context.applicationContext as AnimeVaultApplication).container.uiPreferences
    val subscribedLists by preferences.episodeAlertLists.collectAsStateWithLifecycle()
    val alertPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "rwt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(libraryItemsToCsv(state.items))
            } ?: error("Нельзя открыть файл для записи")
        }.onFailure { android.widget.Toast.makeText(context, "Ошибка экспорта CSV: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }
    LibraryHubScreen(state, viewModel::setList, onOpenLocal, onOpenOnline, onContinue,
        onOpenOfflineLibrary, onOpenHistory, onManageOnline,
        initialTabName = viewModel.selectedTab(), onTabSelected = viewModel::setSelectedTab,
        onExportCsv = { csvPreview = true }, onOpenAlertLists = { subscriptionsDialog = true })
    if (subscriptionsDialog) AlertDialog(
        onDismissRequest = { subscriptionsDialog = false },
        title = { Text("Уведомления по спискам") },
        text = { Column {
            Text("Следить за новыми сериями онлайн-тайтлов выбранных списков. Фоновая проверка примерно раз в 6 часов.")
            listOf(VaultTitleList.WATCHING, VaultTitleList.PLANNED, VaultTitleList.ON_HOLD).forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = status in subscribedLists, onCheckedChange = { enabled ->
                        preferences.setEpisodeAlertLists(if (enabled) subscribedLists + status else subscribedLists - status)
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(context,
                                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            alertPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    })
                    Text(status.title)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { subscriptionsDialog = false }) { Text("Готово") } },
    )
    if (csvPreview) AlertDialog(
        onDismissRequest = { csvPreview = false },
        title = { Text("Экспорт библиотеки") },
        text = { Text("CSV: ${state.items.size} тайтлов. Поля: название, источник, статус, моя оценка, избранное, заметка.\n\n" +
            state.items.take(3).joinToString("\n") { "${it.title} · ${it.list.title} · ${it.rating ?: "без оценки"}" }) },
        confirmButton = { TextButton(onClick = { csvPreview = false; exportCsv.launch("AnimeVault-library.csv") }) { Text("Сохранить CSV") } },
        dismissButton = { TextButton(onClick = { csvPreview = false }) { Text("Отмена") } },
    )
}

@Composable
fun LibraryHubScreen(
    state: LibraryHubUiState,
    onSetList: (String, VaultTitleList) -> Unit,
    onOpenLocal: (Long) -> Unit,
    onOpenOnline: (String, String) -> Unit,
    onContinue: (HomeContinueItem) -> Unit,
    onOpenOfflineLibrary: () -> Unit,
    onOpenHistory: () -> Unit,
    onManageOnline: () -> Unit,
    initialTabName: String = "CONTINUE",
    onTabSelected: (String) -> Unit = {},
    onExportCsv: () -> Unit = {},
    onOpenAlertLists: () -> Unit = {},
) {
    var tab by rememberSaveable {
        mutableStateOf(LibraryHubTab.entries.firstOrNull { it.name == initialTabName } ?: LibraryHubTab.CONTINUE)
    }
    var more by remember { mutableStateOf(false) }
    val entries = remember(state.items, tab) { state.items.filter { item ->
        when (tab) {
            LibraryHubTab.FAVORITES -> item.favorite
            LibraryHubTab.PLANNED -> item.list == VaultTitleList.PLANNED
            LibraryHubTab.WATCHING -> item.list == VaultTitleList.WATCHING
            LibraryHubTab.ON_HOLD -> item.list == VaultTitleList.ON_HOLD
            LibraryHubTab.WATCHED -> item.list == VaultTitleList.WATCHED
            LibraryHubTab.DROPPED -> item.list == VaultTitleList.DROPPED
            LibraryHubTab.RATED -> item.rating != null
            LibraryHubTab.LOCAL -> item.localId != null
            else -> true
        }
    } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column {
            TopAppBar(title = { VaultScreenHeading("Библиотека", "Ваши истории") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    VaultTopBarAction(Icons.Outlined.History, "История просмотра", onOpenHistory)
                    Box {
                        VaultTopBarAction(Icons.Outlined.MoreVert, "Управление библиотекой", { more = true })
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("Папки на устройстве") }, onClick = { more = false; onOpenOfflineLibrary() })
                            DropdownMenuItem(text = { Text("История и избранное онлайн") }, onClick = { more = false; onManageOnline() })
                            DropdownMenuItem(text = { Text("Экспорт библиотеки (CSV)") }, onClick = { more = false; onExportCsv() })
                            DropdownMenuItem(text = { Text("Уведомления по спискам") }, onClick = { more = false; onOpenAlertLists() })
                        }
                    }
                })
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryHubTab.entries) { choice ->
                    val count = when (choice) {
                        LibraryHubTab.CONTINUE -> state.continueWatching.size
                        LibraryHubTab.FAVORITES -> state.items.count { it.favorite }
                        LibraryHubTab.PLANNED -> state.items.count { it.list == VaultTitleList.PLANNED }
                        LibraryHubTab.WATCHING -> state.items.count { it.list == VaultTitleList.WATCHING }
                        LibraryHubTab.ON_HOLD -> state.items.count { it.list == VaultTitleList.ON_HOLD }
                        LibraryHubTab.WATCHED -> state.items.count { it.list == VaultTitleList.WATCHED }
                        LibraryHubTab.DROPPED -> state.items.count { it.list == VaultTitleList.DROPPED }
                        LibraryHubTab.RATED -> state.items.count { it.rating != null }
                        LibraryHubTab.LOCAL -> state.items.count { it.localId != null }
                        LibraryHubTab.ALL -> state.items.size
                    }
                    VaultFilterChip(tab == choice, {
                        tab = choice
                        onTabSelected(choice.name)
                    }, { Text("${choice.title} · $count") })
                }
            }
        }
    }) { padding ->
        when {
            state.loading -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(3) { VaultSkeletonBlock(Modifier.fillMaxWidth().height(160.dp), RoundedCornerShape(12.dp)) }
            }
            tab == LibraryHubTab.CONTINUE && state.continueWatching.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(248.dp), modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.continueWatching, key = HomeContinueItem::stableKey) { item ->
                    ContinueWatchingCard(item, { onContinue(item) }, Modifier.fillMaxWidth())
                }
            }
            tab == LibraryHubTab.CONTINUE || entries.isEmpty() -> VaultEmptyState(
                Icons.Outlined.VideoLibrary, if (tab == LibraryHubTab.CONTINUE) "Нечего продолжать" else "В этом списке пока пусто",
                if (tab == LibraryHubTab.CONTINUE) "Начните смотреть аниме — здесь появится незавершённая серия."
                else "Откройте страницу тайтла и добавьте его в библиотеку.", Modifier.fillMaxSize().padding(padding))
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(entries, key = LibraryHubItem::key) { item ->
                    var menu by remember(item.key) { mutableStateOf(false) }
                    Box {
                        val sharedKey = item.localId?.let { VaultSharedPosterKey("local", it.toString()) }
                            ?: item.providerId?.let { provider -> item.releaseId?.let { VaultSharedPosterKey("online:$provider", it) } }
                        VaultPosterCard(item.title, item.poster, {
                            item.localId?.let(onOpenLocal) ?: item.providerId?.let { provider -> item.releaseId?.let { onOpenOnline(provider, it) } }
                        }, metadata = item.metadata + (item.rating?.let { " · ★$it/5" } ?: ""), sharedKey = sharedKey)
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                            VaultTopBarAction(Icons.Outlined.MoreVert, "Список для ${item.title}", { menu = true })
                            DropdownMenu(menu, { menu = false }) {
                                VaultTitleList.entries.forEach { list ->
                                    DropdownMenuItem(text = { Text((if (item.list == list) "✓ " else "") + list.title) },
                                        onClick = { menu = false; onSetList(item.key, list) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
