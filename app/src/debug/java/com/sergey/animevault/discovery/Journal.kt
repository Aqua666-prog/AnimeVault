package com.sergey.animevault.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Journal(state: DiscoveryState, scroll: SaveableStateHolder) {
    val d = LocalDirection.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.screen in listOf(Screen.Title, Screen.Player, Screen.Downloads)) {
                Action("‹ Назад") { state.back() }
            } else {
                Type("AnimeVault", 23, family = d.display, weight = FontWeight.Bold)
            }
            Meta(if (state.screen == Screen.Player) "Просмотр" else "ЖУРНАЛ", color = d.accent)
        }
        Rule()
        Box(Modifier.weight(1f)) {
            scroll.SaveableStateProvider(state.screen.name) {
                ScrollPage(Modifier.padding(horizontal = 22.dp)) {
                    Spacer(Modifier.height(19.dp))
                    when (state.screen) {
                        Screen.Home -> JournalHome(state)
                        Screen.Catalog -> JournalCatalog(state)
                        Screen.Title -> JournalTitle(state)
                        Screen.Library -> JournalLibrary(state)
                        Screen.Downloads -> JournalDownloads(state)
                        Screen.Player -> JournalPlayer(state)
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
        if (state.screen in listOf(Screen.Home, Screen.Catalog, Screen.Library)) {
            Rule()
            Options(listOf("Журнал" to Screen.Home, "Каталог" to Screen.Catalog, "Библиотека" to Screen.Library),
                state.screen, Modifier.padding(horizontal = 12.dp), state::root)
        }
    }
}

@Composable
private fun JournalHome(state: DiscoveryState) {
    val d = LocalDirection.current
    Meta("КИНОПОДБОРКА  /  НОЯБРЬ", color = d.accent)
    Spacer(Modifier.height(5.dp))
    Heading("Горизонт\nзовёт", 61)
    Spacer(Modifier.height(13.dp))
    Box(Modifier.fillMaxWidth().aspectRatio(1.62f)
        .semantics { contentDescription = films[0].name }
        .clickable(role = Role.Button, onClickLabel = "Открыть тайтл") { state.title(0) }) {
        Art(films[0], Modifier.fillMaxSize())
    }
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Meta("Сигнал над морем", color = d.ink)
        Meta("Фантастика · 2025")
    }
    Spacer(Modifier.height(17.dp))
    Type("Истории о тех, кто слышит море даже вдали от берега.", 19, family = d.display)
    Action("Открыть подборку ›", Modifier.padding(top = 8.dp)) { state.root(Screen.Catalog) }
    Rule()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Meta("ВЫ ОСТАНОВИЛИСЬ")
            Type("Серия 4 · 12:48", 17, weight = FontWeight.SemiBold)
        }
        Action("Продолжить", filled = true) { state.player(index = 0) }
    }
}

@Composable
private fun JournalCatalog(state: DiscoveryState) {
    Meta("УКАЗАТЕЛЬ")
    Heading("Каталог", 54)
    Spacer(Modifier.height(16.dp))
    Search(state)
    Genres(state)
    Spacer(Modifier.height(17.dp))
    Meta("РЕЗУЛЬТАТЫ / ${state.results.size}")
    if (state.results.isEmpty()) EmptyResults(state)
    state.results.forEach { index -> JournalListing(index, state) }
}

@Composable
private fun JournalListing(index: Int, state: DiscoveryState) {
    val film = films[index]
    Rule()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 98.dp).clickable(role = Role.Button) { state.title(index) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(film, Modifier.width(93.dp).height(65.dp))
        Column(Modifier.weight(1f)) {
            Type(film.name, 23, family = LocalDirection.current.display, weight = FontWeight.Bold)
            Meta("${film.genre} · ${film.details}")
        }
        Type("›", 20, color = LocalDirection.current.accent)
    }
}

@Composable
private fun JournalTitle(state: DiscoveryState) {
    val film = state.film
    Meta("${film.genre.uppercase()}  /  ${film.details}")
    Spacer(Modifier.height(5.dp))
    Heading(film.name, 48)
    Spacer(Modifier.height(13.dp))
    Art(film, Modifier.fillMaxWidth().aspectRatio(1.58f))
    Spacer(Modifier.height(13.dp))
    Type(filmSynopsis(state.filmIndex), 17, family = LocalDirection.current.display)
    Spacer(Modifier.height(13.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("Продолжить", Modifier.weight(1f), filled = true) { state.player() }
        Action(if (state.filmIndex in state.saved) "В библиотеке" else "Сохранить",
            Modifier.weight(1f), outlined = true) { state.toggleSaved() }
    }
    Spacer(Modifier.height(17.dp))
    EpisodeList(state, "Содержание")
}

@Composable
private fun JournalLibrary(state: DiscoveryState) {
    val d = LocalDirection.current
    Meta("ЛИЧНОЕ СОБРАНИЕ", color = d.accent)
    Heading("Библиотека", 45)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().background(d.panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Type("Загрузки", 23, family = d.display, weight = FontWeight.Bold)
            Meta("1 готова · 1 в очереди")
        }
        Action("Открыть ›") { state.open(Screen.Downloads) }
    }
    Spacer(Modifier.height(21.dp))
    Heading("Сохранённое", 29)
    state.saved.sorted().forEach { index -> JournalListing(index, state) }
    if (state.saved.isEmpty()) {
        Type("Здесь появятся сохранённые вами тайтлы.", 16)
        Action("Открыть каталог") { state.root(Screen.Catalog) }
    }
}

@Composable
private fun JournalDownloads(state: DiscoveryState) {
    val d = LocalDirection.current
    Meta("БИБЛИОТЕКА  /  ОФЛАЙН", color = d.accent)
    Heading("Загрузки", 51)
    Spacer(Modifier.height(10.dp))
    Type("Серии, которые можно взять с собой.", 20, family = d.display)
    Spacer(Modifier.height(24.dp))
    Heading("В очереди", 28)
    Rule()
    Spacer(Modifier.height(11.dp))
    Type("Сигнал над морем", 23, family = d.display, weight = FontWeight.Bold)
    Meta("Серия 5 · ${if (state.paused) "На паузе" else "Загружается"} · 64%")
    Spacer(Modifier.height(12.dp))
    Progress(0.64f, active = !state.paused)
    Action(if (state.paused) "Возобновить" else "Приостановить") { state.paused = !state.paused }
    Spacer(Modifier.height(15.dp))
    Heading("На устройстве", 28)
    Rule()
    Spacer(Modifier.height(11.dp))
    Art(films[0], Modifier.fillMaxWidth().aspectRatio(2.5f))
    Type("Сигнал над морем", 22, Modifier.padding(top = 10.dp), family = d.display)
    Meta("Серия 4 · 286 МБ · Доступна офлайн")
    Action("Смотреть офлайн", Modifier.padding(top = 9.dp), filled = true) { state.player(index = 0) }
}

@Composable
private fun JournalPlayer(state: DiscoveryState) {
    val d = LocalDirection.current
    Meta("СЕРИЯ ${state.episode}  /  ${state.film.name.uppercase()}", color = d.accent)
    Heading(state.film.episodeTitle(state.episode), 38)
    Spacer(Modifier.height(14.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Art(state.film, Modifier.weight(1.35f).aspectRatio(16f / 9))
                PlayerControls(state, Modifier.weight(1f))
            }
        } else {
            Column {
                Art(state.film, Modifier.fillMaxWidth().aspectRatio(16f / 9))
                Spacer(Modifier.height(12.dp))
                PlayerControls(state)
            }
        }
    }
    if (state.episode < state.film.episodeCount) {
        Spacer(Modifier.height(17.dp))
        Rule()
        Meta("ДАЛЕЕ", Modifier.padding(top = 12.dp))
        Type("Серия ${state.episode + 1} · ${state.film.episodeTitle(state.episode + 1)}", 20, family = d.display)
        Action("Следующая серия ›") { state.player(state.episode + 1) }
    }
}
