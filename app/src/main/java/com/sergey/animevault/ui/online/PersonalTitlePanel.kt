package com.sergey.animevault.ui.online

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import com.sergey.animevault.ui.design.VaultSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.preferences.VaultPersonalTitle

@Composable
internal fun PersonalTitlePanel(
    personal: VaultPersonalTitle,
    translationName: String?,
    onRate: (Int?) -> Unit,
    onSaveNote: (String) -> Unit,
    onNotify: (Boolean) -> Unit,
    allowNotifications: Boolean = true,
) {
    var editNote by remember { mutableStateOf(false) }
    var draft by remember(personal.note, editNote) { mutableStateOf(personal.note) }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Моя оценка", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..5).forEach { rating ->
                    Text(if (rating <= (personal.rating ?: 0)) "★" else "☆",
                        modifier = Modifier.sizeIn(minWidth = VaultSize.touchTarget, minHeight = VaultSize.touchTarget).semantics {
                            contentDescription = "Оценка $rating из 5"
                            selected = personal.rating == rating
                        }.clickable { onRate(if (personal.rating == rating) null else rating) }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text("Оценка личная, не меняет рейтинг каталога.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editNote = true }) {
                    Text(if (personal.note.isBlank()) "Заметка" else "Изменить")
                }
                if (allowNotifications) OutlinedButton(onClick = { onNotify(!personal.notifyEpisodes) }) {
                    Text(if (personal.notifyEpisodes) "Слежение: вкл" else "Новые серии")
                }
            }
            if (personal.note.isNotBlank()) Text(personal.note, style = MaterialTheme.typography.bodyMedium)
            if (personal.notifyEpisodes) Text(
                "Новые серии · ${translationName ?: "все озвучки"} · проверка примерно раз в 6 часов",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (editNote) AlertDialog(
        onDismissRequest = { editNote = false },
        title = { Text("Личная заметка") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
            placeholder = { Text("Впечатления, планы, мысли…") }) },
        confirmButton = { TextButton(onClick = { onSaveNote(draft.trim()); editNote = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editNote = false }) { Text("Отмена") } },
    )
}
