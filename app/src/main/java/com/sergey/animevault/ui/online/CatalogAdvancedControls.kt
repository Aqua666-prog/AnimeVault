package com.sergey.animevault.ui.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.sergey.animevault.ui.preferences.CatalogAdvancedFilter
import com.sergey.animevault.ui.preferences.CatalogPreset
import com.sergey.animevault.ui.preferences.GenreMatchMode

@Composable
internal fun CatalogAdvancedControls(
    filter: CatalogAdvancedFilter,
    availableGenres: List<String>,
    presets: List<CatalogPreset>,
    currentProvider: String,
    onFilter: (CatalogAdvancedFilter) -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (CatalogPreset) -> Unit,
    onDeletePreset: (CatalogPreset) -> Unit,
) {
    var filtersOpen by remember { mutableStateOf(false) }
    var presetsOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { filtersOpen = true }) {
            Text(if (filter.isActive()) "Несколько условий ✓" else "Больше фильтров")
        }
        OutlinedButton(onClick = { presetsOpen = true }) { Text("Мои подборки · ${presets.size}") }
    }
    if (filtersOpen) ModalBottomSheet(onDismissRequest = { filtersOpen = false }) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Точные фильтры", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onFilter(CatalogAdvancedFilter()) }) { Text("Сбросить") }
            }
            Text("Только среди уже загруженных релизов. Новые страницы загружаются отдельно.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Жанры: включить / исключить", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenreMatchMode.entries.forEach { mode ->
                    OutlinedButton(onClick = { onFilter(filter.copy(genreMode = mode)) }) {
                        Text((if (filter.genreMode == mode) "✓ " else "") + if (mode == GenreMatchMode.ANY) "Любой" else "Все")
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(availableGenres, key = { it }) { genre ->
                    val state = when {
                        genre in filter.genres -> "✓ "
                        genre in filter.excludedGenres -> "✕ "
                        else -> "○ "
                    }
                    Text(state + genre, modifier = Modifier.fillMaxWidth().clickable {
                        onFilter(when {
                            genre in filter.genres -> filter.copy(genres = filter.genres - genre,
                                excludedGenres = filter.excludedGenres + genre)
                            genre in filter.excludedGenres -> filter.copy(excludedGenres = filter.excludedGenres - genre)
                            else -> filter.copy(genres = filter.genres + genre)
                        })
                    }.padding(vertical = 9.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedTextField(value = filter.season.orEmpty(),
                onValueChange = { onFilter(filter.copy(season = it.trim().take(40).ifBlank { null })) },
                label = { Text("Сезон (Winter / Spring / Summer / Fall)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = filter.yearFrom?.toString().orEmpty(),
                    onValueChange = { onFilter(filter.copy(yearFrom = it.toIntOrNull()?.takeIf { n -> n in 1900..2200 })) },
                    label = { Text("Год от") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = filter.yearTo?.toString().orEmpty(),
                    onValueChange = { onFilter(filter.copy(yearTo = it.toIntOrNull()?.takeIf { n -> n in 1900..2200 })) },
                    label = { Text("Год до") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = filter.episodesFrom?.toString().orEmpty(),
                    onValueChange = { onFilter(filter.copy(episodesFrom = it.toIntOrNull()?.takeIf { n -> n in 1..2000 })) },
                    label = { Text("Серий от") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = filter.episodesTo?.toString().orEmpty(),
                    onValueChange = { onFilter(filter.copy(episodesTo = it.toIntOrNull()?.takeIf { n -> n in 1..2000 })) },
                    label = { Text("Серий до") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    onFilter(filter.copy(excludeMyLists = !filter.excludeMyLists))
                }) {
                Checkbox(checked = filter.excludeMyLists, onCheckedChange = {
                    onFilter(filter.copy(excludeMyLists = it))
                })
                Text("Скрыть тайтлы из моих списков")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { filtersOpen = false; saveOpen = true }) { Text("Сохранить подборку") }
                TextButton(onClick = { filtersOpen = false }) { Text("Готово") }
            }
        }
    }
    if (presetsOpen) ModalBottomSheet(onDismissRequest = { presetsOpen = false }) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Сохранённые подборки", style = MaterialTheme.typography.titleLarge)
            Text("Каждая подборка помнит источник, запрос и фильтры.", style = MaterialTheme.typography.bodySmall)
            if (presets.isEmpty()) Text("Пока нет сохранённых подборок")
            presets.forEach { preset ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onApplyPreset(preset); presetsOpen = false }) {
                        Text(preset.name + if (preset.providerId == currentProvider) "" else " · ${preset.providerId}")
                    }
                    TextButton(onClick = { onDeletePreset(preset) }) { Text("Удалить") }
                }
            }
            OutlinedButton(onClick = { presetsOpen = false; saveOpen = true }) { Text("Сохранить текущую") }
        }
    }
    if (saveOpen) AlertDialog(onDismissRequest = { saveOpen = false },
        title = { Text("Название подборки") },
        text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(50) },
            label = { Text("Например: романтика 2020–2026") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSavePreset(presetName.trim()); presetName = ""; saveOpen = false },
            enabled = presetName.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("Отмена") } },
    )
}
