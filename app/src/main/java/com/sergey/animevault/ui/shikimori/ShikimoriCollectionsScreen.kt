package com.sergey.animevault.ui.shikimori

import com.sergey.animevault.ui.title.animeTitle
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Public collections feed + local favorites. Existing v3 imports are preserved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShikimoriCollectionsScreen(onBack: () -> Unit, onSearchAnime: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ShikimoriCollectionStore(context,
        (context.applicationContext as? com.sergey.animevault.AnimeVaultApplication)?.container?.animeTitles) }
    val remote = remember { ShikimoriCollectionRepository() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(store.read()) }
    var recent by remember { mutableStateOf(emptyList<ShikimoriCollectionPreview>()) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionLoading by remember { mutableStateOf(false) }
    var busyTopic by remember { mutableStateOf<Long?>(null) }
    var openedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }

    fun openExternal(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
    }

    fun saveCollection(collection: ImportedShikiCollection) {
        saved = (listOf(collection) + saved.filterNot { it.collectionId == collection.collectionId }).take(30)
        store.save(saved)
        openedId = collection.collectionId
        showSaved = true
        actionError = null
    }

    fun loadFeed(nextPage: Int) {
        if (feedLoading || nextPage > 100) return
        scope.launch {
            feedLoading = true
            feedError = null
            try {
                val batch = remote.discover(nextPage)
                recent = if (nextPage == 1) batch else (recent + batch).distinctBy { it.topicId }
                page = nextPage
                hasMore = batch.size >= 20
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                feedError = e.message ?: "Не удалось загрузить каталог Shikimori"
            } finally {
                feedLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Loaded once on opening, with manual refresh and next-page loading available.
        loadFeed(1)
        // Previously imported collections are upgraded quietly, without clearing offline data.
        for (old in saved.filter { it.animes.any { anime -> anime.title.startsWith("Аниме #") || anime.posterUrl == null } }.take(3)) {
            try {
                val updated = remote.refreshSaved(old)
                saved = saved.map { if (it.collectionId == old.collectionId) updated else it }
                store.save(saved)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Existing offline collections remain available during network failures.
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Коллекции Shikimori") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { loadFeed(1) }, enabled = !feedLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Обновить подборки")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Аниме-подборки", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Text("Находите коллекции автоматически или сохраняйте свои по ссылке.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showSaved, onClick = { showSaved = false },
                            label = { Text("Новые") })
                        FilterChip(selected = showSaved, onClick = { showSaved = true },
                            label = { Text("Сохранённые · ${saved.size}") })
                    }
                    OutlinedButton(onClick = { showImport = !showImport }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (showImport) "Скрыть ручной импорт" else "Добавить подборку по ссылке")
                    }
                }
            }
            if (showImport) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = importUrl,
                                onValueChange = { importUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Ссылка Shikimori") },
                                placeholder = { Text("https://shikimori.one/collections/123...") },
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        actionLoading = true
                                        actionError = null
                                        try {
                                            val imported = remote.importFromUrl(importUrl)
                                            saveCollection(imported)
                                            importUrl = ""
                                            showImport = false
                                            Toast.makeText(context, "Добавлено: ${imported.animes.size} аниме", Toast.LENGTH_SHORT).show()
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (e: Exception) {
                                            actionError = e.message ?: "Не удалось добавить коллекцию"
                                        } finally {
                                            actionLoading = false
                                        }
                                    }
                                },
                                enabled = importUrl.isNotBlank() && !actionLoading && busyTopic == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (actionLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Импортировать")
                            }
                            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            if (!showSaved) {
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("Поиск в загруженных подборках") },
                        singleLine = true,
                    )
                }
                if (recent.isEmpty() && feedLoading) {
                    item { LoadingLine() }
                }
                if (recent.isEmpty() && !feedLoading && feedError == null) {
                    item { HintLine("Подборок пока нет. Можно добавить ссылку вручную.") }
                }
                feedError?.let { item { ErrorLine(it) } }
                val filtered = recent.filter { it.title.contains(search.trim(), ignoreCase = true) }
                items(filtered, key = { "preview-${it.topicId}" }) { preview ->
                    val alreadySaved = saved.any { it.collectionId == preview.collectionId }
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(preview.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 3,
                                overflow = TextOverflow.Ellipsis)
                            if (preview.commentsCount > 0) {
                                Text("Обсуждений: ${preview.commentsCount}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    enabled = busyTopic == null && !actionLoading,
                                    onClick = {
                                        if (alreadySaved) {
                                            showSaved = true
                                            openedId = preview.collectionId
                                        } else {
                                            scope.launch {
                                                busyTopic = preview.topicId
                                                actionError = null
                                                try {
                                                    saveCollection(remote.importTopic(preview.topicId, preview.collectionId))
                                                } catch (cancel: CancellationException) {
                                                    throw cancel
                                                } catch (e: Exception) {
                                                    actionError = e.message ?: "Ошибка загрузки"
                                                } finally {
                                                    busyTopic = null
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    if (busyTopic == preview.topicId) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else Text(if (alreadySaved) "Открыть" else "Добавить")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    openExternal("https://shikimori.one/topics/${preview.topicId}")
                                }) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                                    Text("Shikimori")
                                }
                            }
                        }
                    }
                }
                if (actionError != null && !showImport) {
                    item { ErrorLine(actionError ?: "Ошибка") }
                }
                if (hasMore && recent.isNotEmpty() && search.isBlank()) {
                    item {
                        OutlinedButton(
                            onClick = { loadFeed(page + 1) },
                            enabled = !feedLoading,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            if (feedLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Загрузить ещё")
                        }
                    }
                }
            } else {
                if (saved.isEmpty()) {
                    item { HintLine("Сохранённых подборок пока нет. Откройте вкладку «Новые».") }
                }
                saved.forEach { collection ->
                    item(key = "saved-${collection.collectionId}") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                            onClick = {
                                openedId = if (openedId == collection.collectionId) null else collection.collectionId
                            },
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val cover = collection.animes.firstOrNull { it.posterUrl != null }?.posterUrl
                                    if (cover != null) {
                                        AsyncImage(model = cover, contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(width = 66.dp, height = 92.dp)
                                                .clip(RoundedCornerShape(10.dp)))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(collection.title, style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold, maxLines = 3,
                                            overflow = TextOverflow.Ellipsis)
                                        Text("${collection.animes.size} аниме · ${if (openedId == collection.collectionId) "Свернуть" else "Развернуть"}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        if (!actionLoading) scope.launch {
                                            actionLoading = true
                                            actionError = null
                                            try {
                                                saveCollection(remote.refreshSaved(collection))
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (e: Exception) {
                                                actionError = e.message ?: "Не удалось обновить"
                                            } finally {
                                                actionLoading = false
                                            }
                                        }
                                    }, enabled = !actionLoading) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                                        Text("Обновить")
                                    }
                                    TextButton(onClick = { openExternal(collection.sourceUrl) }) {
                                        Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                                        Text("Сайт")
                                    }
                                    TextButton(onClick = {
                                        saved = saved.filterNot { it.collectionId == collection.collectionId }
                                        store.save(saved)
                                        if (openedId == collection.collectionId) openedId = null
                                    }) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                    }
                    if (openedId == collection.collectionId) {
                        items(collection.animes, key = { "anime-${collection.collectionId}-${it.id}" }) { anime ->
                            AnimeCollectionEntry(anime, onSearchAnime) {
                                openExternal("https://shikimori.one/animes/${anime.id}")
                            }
                        }
                    }
                }
                if (actionError != null) item { ErrorLine(actionError ?: "Ошибка") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AnimeCollectionEntry(anime: ImportedShikiAnime, onSearchAnime: (String) -> Unit, onExternal: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
    ) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 62.dp, height = 88.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (anime.posterUrl != null) {
                    AsyncImage(
                        model = anime.posterUrl,
                        contentDescription = "Постер ${animeTitle(anime)}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null,
                        modifier = Modifier.padding(16.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(animeTitle(anime), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("Shikimori #${anime.id}")
                        anime.year?.let { append(" · $it") }
                        anime.score?.let { append(" · ★ $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (anime.title.startsWith("Аниме #")) {
                    Text("Название не получено из API", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (!anime.title.startsWith("Аниме #")) {
                IconButton(onClick = { onSearchAnime(anime.title) }) {
                    Icon(Icons.Outlined.Search, contentDescription = "Найти в AnimeVault")
                }
            }
            IconButton(onClick = onExternal) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = "На Shikimori")
            }
        }
    }
}

@Composable
private fun LoadingLine() {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun HintLine(message: String) {
    Text(message, modifier = Modifier.padding(20.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorLine(message: String) {
    Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error)
}
