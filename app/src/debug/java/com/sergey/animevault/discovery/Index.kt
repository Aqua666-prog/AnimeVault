package com.sergey.animevault.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Index(state: DiscoveryState, scroll: SaveableStateHolder) {
    val d = LocalDirection.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(d.panel).heightIn(min = 56.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.screen in listOf(Screen.Title, Screen.Player)) {
                Action("‹ Назад") { state.back() }
            } else {
                Type("AnimeVault", 22, weight = FontWeight.Bold, tracking = -0.5f)
            }
            Meta(if (state.screen == Screen.Player) "СЕРИЯ ${state.episode}" else "ИНДЕКС", color = d.accent)
        }
        if (state.screen !in listOf(Screen.Title, Screen.Player)) {
            Options(listOf("Обзор" to Screen.Home, "Поиск" to Screen.Catalog,
                "Списки" to Screen.Library, "Офлайн" to Screen.Downloads), state.screen, onSelect = state::root)
        }
        Rule()
        Box(Modifier.weight(1f)) {
            scroll.SaveableStateProvider(state.screen.name) {
                ScrollPage(Modifier.padding(horizontal = 17.dp)) {
                    Spacer(Modifier.height(18.dp))
                    when (state.screen) {
                        Screen.Home -> IndexHome(state)
                        Screen.Catalog -> IndexCatalog(state)
                        Screen.Title -> IndexTitle(state)
                        Screen.Library -> IndexLibrary(state)
                        Screen.Downloads -> IndexDownloads(state)
                        Screen.Player -> IndexPlayer(state)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun IndexHome(state: DiscoveryState) {
    val d = LocalDirection.current
    Heading("Обзор", 45)
    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth().background(d.panel)) {
        IndexMetric("01", "На устройстве", Modifier.weight(1f)) { state.root(Screen.Downloads) }
        IndexMetric(state.saved.size.toString().padStart(2, '0'), "В списке", Modifier.weight(1f)) {
            state.root(Screen.Library)
        }
        IndexMetric("01", "В очереди", Modifier.weight(1f)) { state.root(Screen.Downloads) }
    }
    Spacer(Modifier.height(24.dp))
    Meta("ПРОДОЛЖИТЬ ПРОСМОТР", color = d.accent)
    Spacer(Modifier.height(7.dp))
    Rule()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { state.player(index = 0) }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(films[0], Modifier.width(71.dp).height(69.dp))
        Column(Modifier.weight(1f)) {
            Type(films[0].name, 20, weight = FontWeight.Bold)
            Meta("СЕРИЯ 04 · 12:48")
        }
        Type("▶", 18, color = d.accent)
    }
    Progress(0.54f)
    Spacer(Modifier.height(23.dp))
    Heading("Активная загрузка", 24)
    IndexDownloadRow(state, false)
    Action("Вся очередь ›") { state.root(Screen.Downloads) }
    Spacer(Modifier.height(14.dp))
    Heading("В списке", 24)
    state.saved.sorted().forEach { IndexRecord(it, state) }
}

@Composable
private fun IndexMetric(number: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val d = LocalDirection.current
    Column(
        modifier.heightIn(min = 90.dp).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Type(number, 28, color = d.accent, family = d.data, weight = FontWeight.Bold)
        Type(label, 12, color = d.quiet)
    }
}

@Composable
private fun IndexCatalog(state: DiscoveryState) {
    Heading("Поиск", 45)
    Spacer(Modifier.height(12.dp))
    Search(state)
    Genres(state)
    Spacer(Modifier.height(21.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Meta("НАЗВАНИЕ")
        Meta("НАЙДЕНО / ${state.results.size}", color = LocalDirection.current.accent)
    }
    Spacer(Modifier.height(8.dp))
    if (state.results.isEmpty()) EmptyResults(state)
    state.results.forEach { IndexRecord(it, state) }
    Rule()
}

@Composable
private fun IndexRecord(index: Int, state: DiscoveryState) {
    val d = LocalDirection.current
    val film = films[index]
    Rule()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button) { state.title(index) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(film, Modifier.width(36.dp).height(48.dp))
        Column(Modifier.weight(1f)) {
            Type(film.name, 17, weight = FontWeight.SemiBold)
            Meta("${film.genre} · ${film.details}")
        }
        Type("›", 20, color = d.accent)
    }
}

@Composable
private fun IndexTitle(state: DiscoveryState) {
    val d = LocalDirection.current
    val film = state.film
    Meta("ТАЙТЛ  /  ${film.genre.uppercase()}", color = d.accent)
    Heading(film.name, 38)
    Meta(film.details, Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(15.dp))
    Row(Modifier.fillMaxWidth().background(d.panel), verticalAlignment = Alignment.CenterVertically) {
        Art(film, Modifier.width(80.dp).height(82.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Meta("ПОСЛЕДНЯЯ ПОЗИЦИЯ")
            Type("Серия 4 · 12:48", 17, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Progress(0.54f)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("Серия 4", Modifier.weight(1f), filled = true) { state.player() }
        Action(if (state.filmIndex in state.saved) "✓ В списке" else "+ В список",
            Modifier.weight(1f), outlined = true) { state.toggleSaved() }
    }
    Spacer(Modifier.height(19.dp))
    EpisodeList(state, "Список серий")
    Rule()
    Spacer(Modifier.height(13.dp))
    Heading("Описание", 24)
    Type(filmSynopsis(state.filmIndex), 15, color = d.quiet)
}

@Composable
private fun IndexLibrary(state: DiscoveryState) {
    var watching by rememberSaveable { mutableStateOf(false) }
    Heading("Списки", 45)
    Spacer(Modifier.height(12.dp))
    Options(listOf("Все сохранённые" to false, "Смотрю" to true), watching) { watching = it }
    Spacer(Modifier.height(16.dp))
    val filtered = state.saved.sorted().filter { !watching || it == 0 }
    Meta("ТАЙТЛЫ  /  ${filtered.size}", color = LocalDirection.current.accent)
    Spacer(Modifier.height(8.dp))
    filtered.forEach { IndexRecord(it, state) }
    Rule()
    if (filtered.isEmpty()) {
        Type("В этом списке пока нет тайтлов.", 15, Modifier.padding(top = 12.dp))
        Action("Открыть поиск") { state.root(Screen.Catalog) }
    }
    Spacer(Modifier.height(20.dp))
    Action("Файлы на устройстве ›", Modifier.fillMaxWidth(), outlined = true) { state.root(Screen.Downloads) }
}

@Composable
private fun IndexDownloads(state: DiscoveryState) {
    val d = LocalDirection.current
    Heading("Офлайн", 45)
    Spacer(Modifier.height(11.dp))
    Row(Modifier.fillMaxWidth().background(d.panel).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Meta("НА УСТРОЙСТВЕ")
        Type("286 МБ", 20, color = d.accent, family = d.data, weight = FontWeight.Bold)
    }
    Spacer(Modifier.height(23.dp))
    Heading("Очередь", 25)
    IndexDownloadRow(state, true)
    Spacer(Modifier.height(24.dp))
    Heading("Готовые файлы", 25)
    Rule()
    Row(Modifier.fillMaxWidth().heightIn(min = 73.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Type("Сигнал над морем", 16, weight = FontWeight.SemiBold)
            Meta("СЕРИЯ 04 · 286 МБ · ГОТОВО")
        }
        Action("Смотреть") { state.player(index = 0) }
    }
    Rule()
}

@Composable
private fun IndexDownloadRow(state: DiscoveryState, showTitle: Boolean) {
    Rule()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Type(if (showTitle) "Сигнал над морем" else "Серия 5 · Сигнал над морем",
                16, weight = FontWeight.SemiBold)
            Meta("СЕРИЯ 05 · ${if (state.paused) "ПАУЗА" else "ЗАГРУЖАЕТСЯ"} · 64%")
        }
        Action(if (state.paused) "Возобновить" else "Пауза") { state.paused = !state.paused }
    }
    Progress(0.64f, active = !state.paused)
}

@Composable
private fun IndexPlayer(state: DiscoveryState) {
    val d = LocalDirection.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.background(d.panel).padding(12.dp)) {
                Meta("1080p  /  ЯПОНСКИЙ", color = d.accent)
                PlayerControls(state)
            }
        }
        Column {
            Heading(state.film.name, 28)
            Type(state.film.episodeTitle(state.episode), 16, color = d.quiet)
            Spacer(Modifier.height(12.dp))
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Art(state.film, Modifier.weight(1.45f).aspectRatio(16f / 9))
                    panel(Modifier.weight(1f))
                }
            } else {
                Art(state.film, Modifier.fillMaxWidth().aspectRatio(16f / 9))
                panel(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(13.dp))
            EpisodeList(state, "Перейти к серии")
        }
    }
}
