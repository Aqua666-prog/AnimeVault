package com.sergey.animevault.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.ui.components.VaultEmptyState
import com.sergey.animevault.ui.components.VaultSectionHeader
import com.sergey.animevault.ui.components.VaultStatusPill
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultSurfaceRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatisticsRoute(
    viewModel: StatisticsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatisticsScreen(state = state, onBack = onBack)
}

@Composable
fun StatisticsScreen(
    state: StatisticsSnapshot,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text("Статистика", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (state.localEpisodeCount == 0L && state.onlineHistoryCount == 0) {
            VaultEmptyState(
                icon = Icons.Outlined.BarChart,
                title = "Пока нечего считать",
                body = "Начните смотреть локальные или онлайн-серии. AnimeVault соберёт статистику автоматически.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Outlined.Schedule, formatWatchTime(state.watchedTimeMs), "зафиксировано локально", Modifier.weight(1f))
                    StatTile(Icons.Outlined.Movie, state.completedEpisodeCount.toString(), "серий завершено", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Outlined.BarChart, state.localTitleCount.toString(), "тайтлов локально", Modifier.weight(1f))
                    StatTile(Icons.Outlined.Favorite, state.onlineFavoriteCount.toString(), "избранных онлайн", Modifier.weight(1f))
                }
            }
            item {
                VaultSectionHeader(
                    title = "Активность · 28 дней",
                    supporting = "${state.activeDays} активных дней · по последней отметке каждой локальной серии и онлайн-релиза",
                )
                ActivityHeatmap(state.activity)
            }
            if (state.topGenres.isNotEmpty()) {
                item {
                    VaultSectionHeader(title = "Жанры", supporting = "По метаданным AniList локальной коллекции")
                    VaultPanel(role = VaultSurfaceRole.Card, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(VaultSpacing.lg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.topGenres.forEachIndexed { index, genre ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(genre.name, Modifier.weight(1f).padding(start = 12.dp))
                                    VaultStatusPill("${genre.count}")
                                }
                            }
                        }
                    }
                }
            }
            item {
                VaultSectionHeader(title = "Сводка")
                VaultPanel(role = VaultSurfaceRole.Quiet, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(VaultSpacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryLine("Локальных серий", state.localEpisodeCount.toString())
                        SummaryLine("Онлайн-релизов в истории", state.onlineHistoryCount.toString())
                        if (state.onlineTrackedTimeMs > 0L) SummaryLine("Зафиксировано онлайн", formatWatchTime(state.onlineTrackedTimeMs))
                        state.averageCompletedEpisodeMinutes?.let { SummaryLine("Среднее на завершённую серию", "$it мин") }
                        state.averageAniListScore?.let { SummaryLine("Средний AniList score", "${it / 10.0}/10") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier) {
    VaultPanel(modifier = modifier, role = VaultSurfaceRole.Card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActivityHeatmap(days: List<ActivityDay>) {
    if (days.isEmpty()) return
    val dayFormatter = SimpleDateFormat("d MMMM", Locale.getDefault())
    val max = days.maxOfOrNull(ActivityDay::count)?.coerceAtLeast(1) ?: 1
    VaultPanel(role = VaultSurfaceRole.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { day ->
                        val strength = day.count.toFloat() / max.toFloat()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .semantics {
                                    contentDescription = if (day.count > 0) {
                                        "${dayFormatter.format(Date(day.dayStartMs))}: ${day.count} просмотров"
                                    } else {
                                        "${dayFormatter.format(Date(day.dayStartMs))}: без просмотров"
                                    }
                                }
                                .clip(RoundedCornerShape(VaultRadius.micro))
                                .background(
                                    if (day.count == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f + 0.68f * strength),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (day.count > 0) Text(day.count.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            val formatter = SimpleDateFormat("d MMM", Locale.getDefault())
            Text(
                "${formatter.format(Date(days.first().dayStartMs))} – ${formatter.format(Date(days.last().dayStartMs))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatWatchTime(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60L
    val rest = minutes % 60L
    return when {
        hours > 0L && rest > 0L -> "$hours ч $rest мин"
        hours > 0L -> "$hours ч"
        else -> "$minutes мин"
    }
}
