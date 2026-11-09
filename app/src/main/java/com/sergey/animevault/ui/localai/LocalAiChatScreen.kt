package com.sergey.animevault.ui.localai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiChatScreen(viewModel: LocalAiChatViewModel, onBack: () -> Unit, onTitle: (AiTitleLink) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var recommend by rememberSaveable { mutableStateOf(false) }
    var online by rememberSaveable { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.install(uri)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.size)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.pause() }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Локальный ИИ") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Назад") }
        }, actions = { TextButton(onClick = viewModel::clear, enabled = !state.busy) { Text("Очистить") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MNN-Pocket-0.3B · CPU · Apache 2.0", style = MaterialTheme.typography.titleSmall)
                        Text("Ответы создаются на устройстве. Экспериментальная модель для английского и китайского: в русских ответах встречаются бессмыслица и повторы. Проверяйте рекомендации.", style = MaterialTheme.typography.bodySmall)
                        Text(state.status)
                        state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()); Text("${(it * 100).toInt()} %") }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!state.installed) {
                            Text("Для чата установите модель: 175 МБ, нужно не менее 210 МБ свободного места. Импорт: папка с шестью файлами официального комплекта, включая LICENSE.txt.")
                            Button(onClick = { viewModel.install(null) }, enabled = !state.busy) { Text("Скачать модель") }
                            OutlinedButton(onClick = { import.launch(null) }, enabled = !state.busy) { Text("Импортировать папку") }
                        } else {
                            TextButton(onClick = viewModel::deleteModel, enabled = !state.busy) { Text("Удалить модель") }
                        }
                    }
                }
                itemsIndexed(state.turns) { _, turn ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (turn.user) "Вы" else "MNN-Pocket", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(if (turn.links.isNotEmpty()) "Модель выбрала:" else turn.text.ifEmpty { "…" }) }
                            turn.links.forEach { link ->
                                OutlinedButton(onClick = { onTitle(link) }) { Text(link.name) }
                            }
                        }
                    }
                }
            }
            Row {
                Checkbox(recommend, { recommend = it }, enabled = !state.busy)
                Text("Рекомендовать аниме", Modifier.padding(top = 12.dp))
            }
            if (recommend) {
                Row {
                    Checkbox(online, { online = it }, enabled = !state.busy)
                    Text("Искать в онлайн-каталоге", Modifier.padding(top = 12.dp))
                }
                Text(if (online) "Поисковая фраза отправляется провайдеру каталога. Генерация остаётся локальной."
                    else "Подбор из библиотеки на устройстве и сохранённых произведений.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(input, { if (it.length <= 2000) input = it }, Modifier.fillMaxWidth(),
                enabled = state.installed && !state.busy, label = { Text("Сообщение") }, maxLines = 4)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.send(input.trim(), recommend, online); input = "" },
                    enabled = state.installed && !state.busy && input.isNotBlank()) { Text("Отправить") }
                if (state.busy) OutlinedButton(onClick = viewModel::stop) { Text("Остановить") }
            }
        }
    }
}
