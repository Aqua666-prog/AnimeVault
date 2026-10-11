package com.sergey.animevault.ui.shikimori

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShikimoriCollectionsScreen(onBack: () -> Unit, onSearchAnime: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ShikimoriCollectionStore(context) }
    val remote = remember { ShikimoriCollectionRepository() }
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var collections by remember { mutableStateOf(store.read()) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openExternal(link: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Коллекции Shikimori") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
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
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null)
                            Text("Импорт подборки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text("Вставьте ссылку на коллекцию Shikimori. Подборка сохранится в AnimeVault для просмотра даже без интернета.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ссылка на коллекцию") },
                            placeholder = { Text("https://shikimori.one/collections/123...") },
                            singleLine = true,
                        )
                        Button(
                            enabled = url.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    loading = true
                                    error = null
                                    try {
                                        val imported = remote.importFromUrl(url)
                                        collections = (listOf(imported) + collections.filterNot {
                                            it.collectionId == imported.collectionId
                                        }).take(30)
                                        store.save(collections)
                                        expandedId = imported.collectionId
                                        url = ""
                                        Toast.makeText(context,
                                            "Импортировано: ${imported.animes.size} аниме",
                                            Toast.LENGTH_SHORT).show()
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (problem: Exception) {
                                        error = problem.message ?: "Не удалось импортировать коллекцию"
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                        ) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else Text("Импортировать")
                        }
                        error?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (collections.isEmpty()) {
                item {
                    Text("Импортированных подборок пока нет.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            collections.forEach { collection ->
                item(key = "collection-${collection.collectionId}") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                        onClick = {
                            expandedId = if (expandedId == collection.collectionId) null else collection.collectionId
                        },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(collection.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${collection.animes.size} аниме · ${if (expandedId == collection.collectionId) "Свернуть" else "Развернуть"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(onClick = { openExternal(collection.sourceUrl) }) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                                    Text("Оригинал")
                                }
                                TextButton(onClick = {
                                    collections = collections.filterNot { it.collectionId == collection.collectionId }
                                    store.save(collections)
                                    if (expandedId == collection.collectionId) expandedId = null
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                    Text("Удалить")
                                }
                            }
                        }
                    }
                }
                if (expandedId == collection.collectionId) {
                    items(collection.animes, key = { "anime-${collection.collectionId}-${it.id}" }) { anime ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        ) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(anime.title, style = MaterialTheme.typography.bodyMedium)
                                    Text("Shikimori #${anime.id}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!anime.title.startsWith("Аниме #")) {
                                    IconButton(onClick = { onSearchAnime(anime.title) }) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Найти в AnimeVault")
                                    }
                                }
                                IconButton(onClick = {
                                    openExternal("https://shikimori.one/animes/${anime.id}")
                                }) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = "Открыть на Shikimori")
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(22.dp)) }
        }
    }
}
