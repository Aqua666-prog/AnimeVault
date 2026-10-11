package com.sergey.animevault.ui.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Movie
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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

/** Search by Russian title when available, otherwise keep the provider-friendly romaji name. */
internal fun calendarProviderSearchTitle(episode: VaultAiringEpisode): String? {
    val display = episode.title.takeUnless { it.startsWith("Без названия · AniList #") }
    val translated = display?.takeIf { name ->
        name.any { ch -> ch in 'А'..'я' || ch == 'Ё' || ch == 'ё' }
    }
    return translated ?: episode.searchTitle.ifBlank { display }
}

/** Keep unclassified entries in "Все", never mislabel them as Japanese. */
internal fun filterCalendarByCountry(
    entries: List<VaultAiringEpisode>, country: String?,
): List<VaultAiringEpisode> = when (country) {
    "JP", "CN" -> entries.filter { it.countryCode == country }
    else -> entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeCalendarScreen(
    onBack: () -> Unit,
    onSearchAnime: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val api = remember { AiringScheduleClient() }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<VaultAiringEpisode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var translating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val bounds = remember(weekOffset) { weekWindow(weekOffset) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    LaunchedEffect(bounds, retry) {
        loading = true
        translating = false
        error = null
        entries = emptyList()
        try {
            val week = api.week(bounds.first, bounds.second)
            entries = week
            loading = false
            if (week.any { it.malId != null }) {
                translating = true
                try {
                    entries = api.withRussianTitles(week)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Translation is optional; the AniList schedule is still useful.
                } finally {
                    translating = false
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (problem: Exception) {
            entries = emptyList()
            error = problem.message ?: "Не удалось загрузить календарь AniList"
        } finally {
            loading = false
        }
    }

    val visible = filterCalendarByCountry(entries, country)
    val nowSeconds = System.currentTimeMillis() / 1000L
    val closest = visible.firstOrNull { it.airingAtSeconds >= nowSeconds }
    val groups = visible.groupBy { dayKeyFormat.format(Date(it.airingAtSeconds * 1000L)) }

    fun openAniList(mediaId: Int) {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://anilist.co/anime/$mediaId")))
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Календарь премьер") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { retry++ }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Обновить расписание")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "week-controls") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { weekOffset-- }, enabled = !loading) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "Предыдущая неделя")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${dateFormat.format(Date(bounds.first * 1000L))} — " +
                                dateFormat.format(Date((bounds.second - 1L) * 1000L)),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Эфиры · время на вашем устройстве",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { weekOffset++ }, enabled = !loading) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "Следующая неделя")
                    }
                }
            }
            item(key = "country-filters") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listOf(null, "JP", "CN"), key = { it ?: "ALL" }) { option ->
                        FilterChip(
                            selected = country == option,
                            onClick = { country = option },
                            label = {
                                Text(when (option) {
                                    "JP" -> "Япония"
                                    "CN" -> "Китай · дунхуа"
                                    else -> "Все страны"
                                })
                            },
                        )
                    }
                }
            }
            if (translating && !loading) item(key = "translation") {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Text(
                        "Уточняем русские названия через Shikimori…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (loading) item(key = "loading") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }
            }
            if (error != null) item(key = "error") {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retry++ }) { Text("Повторить") }
                }
            }
            if (!loading && error == null && visible.isEmpty()) item(key = "empty") {
                Text(
                    "Нет подтверждённых эфиров для выбранного фильтра и периода.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!loading && error == null) {
                groups.forEach { (day, episodes) ->
                    item(key = "day-$day") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                dayFormat.format(Date(episodes.first().airingAtSeconds * 1000L))
                                    .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${episodes.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    items(episodes, key = { "${it.mediaId}:${it.episode}:${it.airingAtSeconds}" }) { item ->
                        val upcoming = item == closest
                        val searchQuery = calendarProviderSearchTitle(item)
                        val hasSearch = !searchQuery.isNullOrBlank()
                        Surface(
                            onClick = {
                                if (searchQuery != null) onSearchAnime(searchQuery)
                                else openAniList(item.mediaId)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (upcoming) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(modifier = Modifier.size(width = 76.dp, height = 100.dp)
                                    .clip(RoundedCornerShape(10.dp))) {
                                    item.coverUrl?.let { url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = "Постер ${item.title}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } ?: Icon(
                                        Icons.Outlined.Movie,
                                        contentDescription = "Постер отсутствует",
                                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    if (upcoming) {
                                        Text(
                                            "Ближайший эфир",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "Серия ${item.episode} · ${timeFormat.format(Date(item.airingAtSeconds * 1000L))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        when (item.countryCode) {
                                            "JP" -> "Япония"
                                            "CN" -> "Китай"
                                            else -> "Страна не указана"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        enabled = hasSearch,
                                        onClick = { if (searchQuery != null) onSearchAnime(searchQuery) },
                                    ) {
                                        Icon(Icons.Outlined.Search, contentDescription = "Искать в AnimeVault")
                                    }
                                    IconButton(onClick = { openAniList(item.mediaId) }) {
                                        Icon(Icons.Outlined.OpenInNew, contentDescription = "Открыть на AniList")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                    Text(
                        "Нажмите на карточку или лупу для поиска в AnimeVault. Стрелка откроет AniList.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Время по часовому поясу устройства. Эфир не гарантирует наличие серии у видеопровайдера.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
