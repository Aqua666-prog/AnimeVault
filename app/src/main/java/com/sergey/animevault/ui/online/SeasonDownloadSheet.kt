package com.sergey.animevault.ui.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergey.animevault.data.download.SeasonQualityPolicy
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.util.formatEpisodeNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonDownloadSheet(
    episodes: List<OnlineEpisode>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStart: (List<String>, Int?, SeasonQualityPolicy, Boolean) -> Unit,
) {
    val available = remember(episodes) { episodes.filter { it.hasStream }.distinctBy { it.id } }
    var selected by remember(available) { mutableStateOf(available.map { it.id }.toSet()) }
    var quality by remember { mutableStateOf<Int?>(720) }
    var policy by remember { mutableStateOf(SeasonQualityPolicy.LOWER) }
    var wifiOnly by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Скачать сезон", style = MaterialTheme.typography.titleLarge)
            Text("Серии будут скачиваться по очереди. Готовые серии повторно не скачиваются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрано ${selected.size} из ${available.size}", modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == available.size) emptySet() else available.map { it.id }.toSet() }) {
                    Text(if (selected.size == available.size) "Снять все" else "Выбрать все")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                items(available, key = OnlineEpisode::id) { episode ->
                    val checked = episode.id in selected
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (checked) selected - episode.id else selected + episode.id
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = {
                            selected = if (it) selected + episode.id else selected - episode.id
                        })
                        Text("Серия ${episode.ordinal?.let(::formatEpisodeNumber) ?: (available.indexOf(episode) + 1).toString()}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("Качество", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(null to "Авто", 360 to "360p", 480 to "480p", 720 to "720p", 1080 to "1080p")) { (value, label) ->
                    FilterChip(selected = quality == value, onClick = { quality = value }, label = { Text(label) })
                }
            }
            if (quality != null) {
                Text("Если качество недоступно", style = MaterialTheme.typography.titleSmall)
                listOf(
                    SeasonQualityPolicy.LOWER to "Ближайшее более низкое",
                    SeasonQualityPolicy.ANY to "Любое доступное",
                    SeasonQualityPolicy.STRICT to "Пропустить серию",
                ).forEach { (choice, label) ->
                    Row(Modifier.fillMaxWidth().clickable { policy = choice }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = policy == choice, onClick = { policy = choice })
                        Text(label)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { wifiOnly = !wifiOnly }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                Text("Только сеть без тарификации (обычно Wi-Fi)")
            }
            Button(
                onClick = { onStart(available.map { it.id }.filter { it in selected }, quality, policy, wifiOnly) },
                enabled = selected.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Подготовка очереди…" else "Добавить ${selected.size} серий в очередь") }
            Spacer(Modifier.height(10.dp))
        }
    }
}
