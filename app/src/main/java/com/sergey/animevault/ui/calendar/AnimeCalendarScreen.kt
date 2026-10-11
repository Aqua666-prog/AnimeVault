package com.sergey.animevault.ui.calendar

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.sergey.animevault.data.metadata.AiringScheduleClient
import com.sergey.animevault.data.metadata.VaultAiringEpisode
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun weekWindow(offset: Int): Pair<Long, Long> {
    val first = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset * 7)
    }
    val end = (first.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
    return first.timeInMillis / 1000L to end.timeInMillis / 1000L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeCalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { AiringScheduleClient() }
    var weekOffset by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<VaultAiringEpisode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val bounds = remember(weekOffset) { weekWindow(weekOffset) }
    val format = remember { SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    LaunchedEffect(bounds, retry) {
        loading = true
        error = null
        try {
            entries = api.week(bounds.first, bounds.second)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (problem: Exception) {
            entries = emptyList()
            error = problem.message ?: "Не удалось загрузить календарь"
        } finally {
            loading = false
        }
    }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Календарь премьер") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { weekOffset-- }) {
                        Icon(Icons.Outlined.ChevronLeft, "Предыдущая неделя")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${dateFormat.format(Date(bounds.first * 1000))} — ${dateFormat.format(Date((bounds.second - 1) * 1000))}",
                            fontWeight = FontWeight.Bold)
                        Text("Японский эфир · местное время", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { weekOffset++ }) {
                        Icon(Icons.Outlined.ChevronRight, "Следующая неделя")
                    }
                }
            }
            if (loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(22.dp))
            } }
            if (error != null) item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retry++ }) { Text("Повторить") }
                }
            }
            if (!loading && error == null && entries.isEmpty()) item {
                Text("На выбранные дни нет подтверждённых эфиров.", modifier = Modifier.padding(20.dp))
            }
            if (!loading && error == null) {
                items(entries, key = { "${it.mediaId}:${it.episode}" }) { item ->
                    Surface(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://anilist.co/anime/${item.mediaId}")))
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            item.coverUrl?.takeIf(String::isNotBlank)?.let { url ->
                                AsyncImage(model = url, contentDescription = null,
                                    modifier = Modifier.size(68.dp),
                                    contentScale = ContentScale.Crop)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Серия ${item.episode} · ${format.format(Date(item.airingAtSeconds * 1000))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Text("Расписание AniList не означает, что серия уже доступна в Kodik или другом источнике.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
}
