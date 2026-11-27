package com.sergey.animevault.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Session(state: DiscoveryState, scroll: SaveableStateHolder) {
    Column(Modifier.fillMaxSize()) {
        if (state.screen != Screen.Player) SessionHeader(state)
        Box(Modifier.weight(1f)) {
            scroll.SaveableStateProvider(state.screen.name) {
                ScrollPage {
                    when (state.screen) {
                        Screen.Home -> SessionHome(state)
                        Screen.Catalog -> SessionCatalog(state)
                        Screen.Title -> SessionTitle(state)
                        Screen.Library -> SessionLibrary(state)
                        Screen.Downloads -> SessionDownloads(state)
                        Screen.Player -> SessionPlayer(state)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(state: DiscoveryState) {
    val d = LocalDirection.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (state.screen) {
            Screen.Title, Screen.Downloads -> Action("‹ Назад") { state.back() }
            else -> Type("AnimeVault", 22, weight = FontWeight.Bold, tracking = -0.6f)
        }
        if (state.screen == Screen.Home) Meta("СЕАНС", color = d.accent)
        if (state.screen == Screen.Library) Action("Загрузки ↓") { state.open(Screen.Downloads) }
    }
    if (state.screen in listOf(Screen.Home, Screen.Catalog, Screen.Library)) {
        Options(listOf("Сеанс" to Screen.Home, "Афиша" to Screen.Catalog, "Моё" to Screen.Library),
            state.screen, Modifier.padding(horizontal = 16.dp), state::root)
    }
    Rule()
}

@Composable
private fun SessionHome(state: DiscoveryState) {
    val d = LocalDirection.current
    Art(films[0], Modifier.fillMaxWidth().aspectRatio(1.53f))
    Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
        Meta("ПРОДОЛЖИТЬ ПРОСМОТР", color = d.accent)
        Spacer(Modifier.height(5.dp))
        Heading("Сигнал\nнад морем", 45)
        Spacer(Modifier.height(8.dp))
        Type("Серия 4 · Береговая линия", 17, color = d.quiet)
        Spacer(Modifier.height(12.dp))
        Progress(0.54f)
        Meta("12:48 из 23:42", Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Action("▶  Продолжить", Modifier.weight(1.4f), filled = true) { state.player(index = 0) }
            Action("О тайтле", Modifier.weight(1f), outlined = true) { state.title(0) }
        }
        Spacer(Modifier.height(23.dp))
        Heading("Следом в очереди", 24)
        Spacer(Modifier.height(8.dp))
        SessionStrip(1, state)
    }
}

@Composable
private fun SessionCatalog(state: DiscoveryState) {
    Column(Modifier.padding(horizontal = 19.dp, vertical = 19.dp)) {
        Heading("Афиша", 43)
        Spacer(Modifier.height(12.dp))
        Search(state)
        Genres(state)
        Spacer(Modifier.height(14.dp))
        if (state.results.isEmpty()) EmptyResults(state)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = when {
                LocalDensity.current.fontScale >= 1.45f || maxWidth < 340.dp -> 1
                maxWidth >= 660.dp -> 3
                else -> 2
            }
            Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                state.results.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { index ->
                            val film = films[index]
                            Column(Modifier.weight(1f).clickable(role = Role.Button) { state.title(index) }) {
                                Art(film, Modifier.fillMaxWidth().aspectRatio(if (columns == 1) 1.75f else 0.84f))
                                Type(film.name, 19, Modifier.padding(top = 7.dp), weight = FontWeight.SemiBold)
                                Meta(film.genre)
                                Meta(film.details)
                            }
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTitle(state: DiscoveryState) {
    val film = state.film
    val d = LocalDirection.current
    Art(film, Modifier.fillMaxWidth().aspectRatio(1.65f))
    Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
        Meta("${film.genre.uppercase()}  ·  ${film.details}", color = d.accent)
        Heading(film.name, 41, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(11.dp))
        Action("▶  Продолжить серию 4", Modifier.fillMaxWidth(), filled = true) { state.player() }
        Action(if (state.filmIndex in state.saved) "✓ В моём списке" else "+ В мой список") { state.toggleSaved() }
        Type(filmSynopsis(state.filmIndex), 15, color = d.quiet)
        Spacer(Modifier.height(16.dp))
        EpisodeList(state)
    }
}

@Composable
private fun SessionLibrary(state: DiscoveryState) {
    val d = LocalDirection.current
    Column(Modifier.padding(horizontal = 19.dp, vertical = 19.dp)) {
        Heading("Моё", 45)
        Type("Продолжайте с того места, где остановились.", 16, color = d.quiet)
        Spacer(Modifier.height(19.dp))
        Art(films[0], Modifier.fillMaxWidth().aspectRatio(2.35f))
        Column(Modifier.fillMaxWidth().background(d.panel).padding(12.dp)) {
            Type(films[0].name, 20, weight = FontWeight.Bold)
            Meta("Серия 4 · 12:48 из 23:42")
            Spacer(Modifier.height(10.dp))
            Progress(0.54f)
            Action("Продолжить") { state.player(index = 0) }
        }
        Spacer(Modifier.height(20.dp))
        Heading("Мой список", 26)
        state.saved.sorted().forEach { SessionStrip(it, state) }
        if (state.saved.isEmpty()) {
            Type("Ваш список пока пуст.", 15)
            Action("Открыть афишу") { state.root(Screen.Catalog) }
        }
    }
}

@Composable
private fun SessionStrip(index: Int, state: DiscoveryState) {
    val film = films[index]
    Rule()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(role = Role.Button) { state.title(index) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(film, Modifier.width(90.dp).height(55.dp))
        Column(Modifier.weight(1f)) {
            Type(film.name, 17, weight = FontWeight.SemiBold)
            Meta(film.genre)
        }
        Meta("›")
    }
}

@Composable
private fun SessionDownloads(state: DiscoveryState) {
    val d = LocalDirection.current
    Column(Modifier.padding(horizontal = 19.dp, vertical = 19.dp)) {
        Heading("Смотреть\nофлайн", 43)
        Spacer(Modifier.height(14.dp))
        Art(films[0], Modifier.fillMaxWidth().aspectRatio(1.8f))
        Column(Modifier.fillMaxWidth().background(d.panel).padding(14.dp)) {
            Meta("ГОТОВО К ПРОСМОТРУ", color = d.accent)
            Type("Сигнал над морем", 25, weight = FontWeight.Bold)
            Meta("Серия 4 · 286 МБ")
            Action("▶  Смотреть офлайн", Modifier.fillMaxWidth().padding(top = 13.dp), filled = true) {
                state.player(index = 0)
            }
        }
        Spacer(Modifier.height(22.dp))
        Heading("Очередь", 26)
        Rule()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Type("Серия 5", 18, weight = FontWeight.SemiBold)
                Meta(if (state.paused) "На паузе · 64%" else "Загружается · 64%")
            }
            Action(if (state.paused) "Возобновить" else "Пауза") { state.paused = !state.paused }
        }
        Progress(0.64f, active = !state.paused)
    }
}

@Composable
private fun SessionPlayer(state: DiscoveryState) {
    val d = LocalDirection.current
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0xFF080E15))) {
        val wide = maxWidth >= 620.dp
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Action("‹ Назад") { state.back() }
                Meta(state.film.name, Modifier.weight(1f).padding(start = 12.dp))
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Art(state.film, Modifier.weight(1.7f).aspectRatio(16f / 9))
                    SessionPlayerPanel(state, Modifier.weight(1f))
                }
            } else {
                Art(state.film, Modifier.fillMaxWidth().aspectRatio(16f / 9))
                SessionPlayerPanel(state)
            }
            if (state.episode < state.film.episodeCount) {
                Rule()
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                        Meta("СЛЕДУЮЩИЙ ЭПИЗОД", color = d.accent)
                        Type("Серия ${state.episode + 1} · ${state.film.episodeTitle(state.episode + 1)}", 16)
                    }
                    Action("Далее ›") { state.player(state.episode + 1) }
                }
            }
        }
    }
}

@Composable
private fun SessionPlayerPanel(state: DiscoveryState, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 19.dp, vertical = 14.dp)) {
        Meta("СЕРИЯ ${state.episode}", color = LocalDirection.current.accent)
        Heading(state.film.episodeTitle(state.episode), 29)
        PlayerControls(state)
    }
}
