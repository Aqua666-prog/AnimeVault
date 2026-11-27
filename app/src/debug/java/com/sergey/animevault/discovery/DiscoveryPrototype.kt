package com.sergey.animevault.discovery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sergey.animevault.R
import kotlin.math.roundToInt

internal enum class Concept(val folder: String) { Journal("journal"), Session("session"), Index("index") }
internal enum class Screen(val label: String) {
    Home("Главная"), Catalog("Каталог"), Title("Тайтл"),
    Library("Библиотека"), Downloads("Загрузки"), Player("Плеер")
}

internal data class Direction(
    val paper: Color,
    val ink: Color,
    val quiet: Color,
    val line: Color,
    val outline: Color,
    val panel: Color,
    val accent: Color,
    val onAccent: Color,
    val display: FontFamily,
    val data: FontFamily = FontFamily.SansSerif,
    val motion: Int,
)

private val directions = mapOf(
    Concept.Journal to Direction(Color(0xFFF2EBDF), Color(0xFF242219), Color(0xFF60584A),
        Color(0xFFCDC0AE), Color(0xFF857663), Color(0xFFE6DBCC), Color(0xFF912D21), Color(0xFFFFFFFF),
        FontFamily.Serif, motion = 180),
    Concept.Session to Direction(Color(0xFF0E151F), Color(0xFFF6F5F1), Color(0xFFAFBCCC),
        Color(0xFF374758), Color(0xFF64798F), Color(0xFF1B2736), Color(0xFFF5CE87), Color(0xFF20190C),
        FontFamily.SansSerif, motion = 220),
    Concept.Index to Direction(Color(0xFF111B17), Color(0xFFE6F0E8), Color(0xFFA5B8AC),
        Color(0xFF3B5044), Color(0xFF698573), Color(0xFF1B2A23), Color(0xFFB5F0CA), Color(0xFF13251B),
        FontFamily.SansSerif, FontFamily.Monospace, motion = 100),
)

internal val LocalDirection = staticCompositionLocalOf { directions.getValue(Concept.Session) }

internal data class Fixture(
    val name: String,
    val genre: String,
    val year: Int,
    val episodeCount: Int,
    val art: Int,
    val episode: String,
) {
    val details get() = "$year · $episodeCount серий"

    fun episodeTitle(number: Int): String = when (number) {
        3 -> "Переход"
        4 -> episode
        5 -> "За туманом"
        else -> "Эпизод $number"
    }
}

internal val films = listOf(
    Fixture("Сигнал над морем", "Фантастика", 2025, 12, R.drawable.discovery_signal, "Береговая линия"),
    Fixture("Тихая орбита", "Драма", 2024, 8, R.drawable.discovery_orbit, "Карта ветров"),
    Fixture("Стеклянный прилив", "Фантастика", 2025, 10, R.drawable.discovery_tide, "Рубеж"),
)

@Stable
internal class DiscoveryState(initial: Screen) {
    var screen by mutableStateOf(initial)
        private set
    private var trail by mutableStateOf(emptyList<Screen>())
    var filmIndex by mutableIntStateOf(0)
        private set
    var query by mutableStateOf("")
    var genre by mutableStateOf("Все")
    var saved by mutableStateOf(setOf(0, 1))
    var paused by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var playerOptions by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var subtitles by mutableStateOf(true)
    var position by mutableFloatStateOf(0.54f)
    var episode by mutableIntStateOf(4)
    val film get() = films[filmIndex]
    val results get() = films.indices.filter { index ->
        val film = films[index]
        film.name.contains(query.trim(), ignoreCase = true) && (genre == "Все" || genre == film.genre)
    }

    fun root(destination: Screen) {
        if (screen == destination) return
        trail = if (destination == Screen.Home) emptyList() else listOf(Screen.Home)
        screen = destination
    }

    fun open(destination: Screen) {
        if (screen == destination) return
        trail = trail + screen
        screen = destination
    }

    fun back() {
        if (playerOptions && screen == Screen.Player) {
            playerOptions = false
            return
        }
        screen = trail.lastOrNull() ?: Screen.Home
        trail = trail.dropLast(1)
    }

    fun title(index: Int) {
        if (filmIndex != index) position = 0.54f
        filmIndex = index
        open(Screen.Title)
    }

    fun player(number: Int = 4, index: Int = filmIndex) {
        if (number !in 1..films[index].episodeCount) return
        if (filmIndex != index || episode != number) position = if (number == 4) 0.54f else 0f
        filmIndex = index
        episode = number
        playerOptions = false
        playing = false
        open(Screen.Player)
    }

    fun toggleSaved() {
        saved = if (filmIndex in saved) saved - filmIndex else saved + filmIndex
    }
}

@Composable
internal fun DiscoveryPrototype(
    concept: Concept,
    initial: Screen = Screen.Home,
    motionEnabled: Boolean = true,
) {
    val state = remember(concept, initial) { DiscoveryState(initial) }
    val scroll = rememberSaveableStateHolder()
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = state.screen != Screen.Home) { state.back() }
    }
    val direction = directions.getValue(concept)
    MaterialTheme {
        CompositionLocalProvider(LocalDirection provides if (motionEnabled) direction else direction.copy(motion = 0)) {
            Box(Modifier.fillMaxSize().background(direction.paper)) {
                when (concept) {
                    Concept.Journal -> Journal(state, scroll)
                    Concept.Session -> Session(state, scroll)
                    Concept.Index -> Index(state, scroll)
                }
            }
        }
    }
}

@Composable
internal fun Type(
    value: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalDirection.current.ink,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    tracking: Float = 0f,
) {
    Text(value, modifier, style = TextStyle(
        color = color, fontSize = size.sp, lineHeight = (size * 1.19).sp,
        fontWeight = weight, fontFamily = family, letterSpacing = tracking.sp,
    ))
}

@Composable
internal fun Heading(value: String, size: Int, modifier: Modifier = Modifier) {
    Type(value, size, modifier.semantics { heading() }, weight = FontWeight.Bold,
        family = LocalDirection.current.display, tracking = -0.5f)
}

@Composable
internal fun Meta(value: String, modifier: Modifier = Modifier, color: Color = LocalDirection.current.quiet) {
    Type(value, 12, modifier, color, FontWeight.Medium, LocalDirection.current.data, 0.25f)
}

@Composable
internal fun Rule(color: Color = LocalDirection.current.line) {
    HorizontalDivider(color = color)
}

@Composable
internal fun Art(film: Fixture, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    Image(painterResource(film.art), null, modifier, alignment = alignment, contentScale = ContentScale.Crop)
}

@Composable
internal fun Action(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val d = LocalDirection.current
    val surface = when {
        filled -> modifier.background(d.accent)
        outlined -> modifier.border(1.dp, d.outline)
        else -> modifier
    }
    Box(
        surface.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Type(label, 14, color = if (filled) d.onAccent else d.accent, weight = FontWeight.Bold)
    }
}

@Composable
internal fun Tab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val d = LocalDirection.current
    Box(
        modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val y = size.height - 1.dp.toPx()
                    drawLine(d.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                }
            }.padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Type(label, 13, color = if (selected) d.accent else d.quiet,
            weight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
internal fun <T> Options(
    options: List<Pair<String, T>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.45f
    val group = modifier.fillMaxWidth().selectableGroup()
    if (largeText) {
        FlowRow(group, horizontalArrangement = Arrangement.Start) {
            options.forEach { (label, key) ->
                Tab(label, key == selected, Modifier.widthIn(min = 72.dp)) { onSelect(key) }
            }
        }
    } else {
        Row(group) {
            options.forEach { (label, key) ->
                Tab(label, key == selected, Modifier.weight(1f)) { onSelect(key) }
            }
        }
    }
}

@Composable
internal fun Search(state: DiscoveryState, modifier: Modifier = Modifier) {
    val d = LocalDirection.current
    BasicTextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        textStyle = TextStyle(color = d.ink, fontSize = 16.sp),
        cursorBrush = SolidColor(d.accent),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Поиск аниме" },
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).border(1.dp, d.outline)
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) Type("Найти аниме", 16, color = d.quiet)
                    inner()
                }
                if (state.query.isNotEmpty()) Action("Сбросить") { state.query = "" }
            }
        },
    )
}

@Composable
internal fun Genres(state: DiscoveryState) {
    Options(listOf("Все", "Фантастика", "Драма").map { it to it }, state.genre) { state.genre = it }
}

@Composable
internal fun EmptyResults(state: DiscoveryState) {
    Heading("Ничего не найдено", 25)
    Type("Попробуйте другое название или сбросьте жанр.", 16)
    Action("Сбросить поиск", outlined = true) {
        state.query = ""
        state.genre = "Все"
    }
}

@Composable
internal fun ScrollPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
}

@Composable
internal fun PlayerControls(state: DiscoveryState, modifier: Modifier = Modifier) {
    val d = LocalDirection.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = state.position, onValueChange = { state.position = it },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Позиция воспроизведения" },
            colors = SliderDefaults.colors(thumbColor = d.accent, activeTrackColor = d.accent, inactiveTrackColor = d.outline),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val seconds = (state.position * 1422).roundToInt()
            Meta("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
            Meta("23:42")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Action("−10 с") { state.position = (state.position - 10f / 1422).coerceAtLeast(0f) }
            Action(if (state.playing) "Пауза" else "Смотреть", filled = true) { state.playing = !state.playing }
            Action("+10 с") { state.position = (state.position + 10f / 1422).coerceAtMost(1f) }
        }
        Action(if (state.playerOptions) "Скрыть настройки" else "Звук и субтитры") {
            state.playerOptions = !state.playerOptions
        }
        if (state.playerOptions) {
            Rule()
            Meta("Японская дорожка · 1080p")
            Action("Субтитры: ${if (state.subtitles) "вкл." else "выкл."}", outlined = true) {
                state.subtitles = !state.subtitles
            }
            Action("Скорость ×${state.speed}", outlined = true) {
                state.speed = if (state.speed >= 1.5f) 1f else state.speed + 0.25f
            }
        }
    }
}

@Composable
internal fun Progress(value: Float, active: Boolean = true) {
    val d = LocalDirection.current
    val display by animateFloatAsState(value, tween(d.motion), label = "Прогресс")
    val fill by animateColorAsState(
        if (active) d.accent else d.outline, tween(d.motion), label = "Состояние загрузки",
    )
    Box(Modifier.fillMaxWidth().height(3.dp).background(d.line)) {
        Box(Modifier.fillMaxWidth(display).fillMaxHeight().background(fill))
    }
}

@Composable
internal fun EpisodeList(state: DiscoveryState, heading: String = "Эпизоды") {
    Heading(heading, 25)
    (3..5).forEach { number ->
        val name = state.film.episodeTitle(number)
        Rule()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button) { state.player(number) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Meta("0$number", color = LocalDirection.current.accent)
            Column(Modifier.weight(1f)) {
                Type(name, 16, weight = FontWeight.SemiBold)
                if (number == 4) Meta("Продолжить · 12:48")
            }
            Meta("24 мин")
        }
    }
}


@Preview(name = "Журнал", widthDp = 393, heightDp = 852, locale = "ru")
@Composable private fun JournalPreview() = DiscoveryPrototype(Concept.Journal)
@Preview(name = "Сеанс", widthDp = 393, heightDp = 852, locale = "ru")
@Composable private fun SessionPreview() = DiscoveryPrototype(Concept.Session)
@Preview(name = "Индекс", widthDp = 393, heightDp = 852, locale = "ru")
@Composable private fun IndexPreview() = DiscoveryPrototype(Concept.Index)

internal fun filmSynopsis(index: Int): String = when (index) {
    0 -> "Старый маяк принимает сигналы из будущего. Курьеру предстоит найти их источник до наступления шторма."
    1 -> "Смотрительница заброшенной станции составляет карту тишины и обнаруживает, что она меняется каждую ночь."
    else -> "Город готовится к приливу, который на один вечер возвращает жителям утраченные воспоминания."
}
