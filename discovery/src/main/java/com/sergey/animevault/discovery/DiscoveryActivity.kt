package com.sergey.animevault.discovery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class DiscoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiscoveryLauncher { light ->
                val bars = if (light) {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
                } else {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
        }
    }
}

private val launcherBackground = Color(0xFF111B17)
private val launcherText = Color(0xFFE6F0E8)
private val launcherMuted = Color(0xFFA5B8AC)
private val launcherAccent = Color(0xFFB5F0CA)

private val conceptSaver = Saver<Concept?, String>(
    save = { it?.name.orEmpty() },
    restore = { name -> Concept.entries.firstOrNull { it.name == name } },
)

@Composable
private fun DiscoveryLauncher(onBarAppearance: (Boolean) -> Unit) {
    var concept by rememberSaveable(stateSaver = conceptSaver) { mutableStateOf(null) }
    val current = concept
    val journal = current == Concept.Journal
    val background = when (current) {
        Concept.Journal -> Color(0xFFF2EBDF)
        Concept.Session -> Color(0xFF0E151F)
        else -> launcherBackground
    }
    SideEffect { onBarAppearance(journal) }
    BackHandler(enabled = current != null) { concept = null }
    MaterialTheme(colorScheme = darkColorScheme(background = launcherBackground, onBackground = launcherText)) {
        Column(Modifier.fillMaxSize().background(background).safeDrawingPadding().imePadding()) {
            if (current == null) {
                ConceptPicker { concept = it }
            } else {
                val textColor = if (journal) Color(0xFF912D21) else launcherAccent
                Text(
                    "‹  Все концепции",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { concept = null }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge,
                )
                DiscoveryPrototype(current)
            }
        }
    }
}

@Composable
private fun ConceptPicker(onSelect: (Concept) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("AnimeVault Preview", color = launcherAccent, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(14.dp))
        Text(
            "Выберите концепцию",
            modifier = Modifier.semantics { heading() },
            color = launcherText,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text("Три интерфейса на одинаковых тестовых данных.", color = launcherMuted)
        Spacer(Modifier.height(24.dp))
        ConceptOption("Журнал", "Выбрать аниме через редакционную подборку") { onSelect(Concept.Journal) }
        ConceptOption("Сеанс", "Вернуться к незаконченной серии") { onSelect(Concept.Session) }
        ConceptOption("Индекс", "Управлять списками и офлайн-загрузками") { onSelect(Concept.Index) }
        HorizontalDivider(color = Color(0xFF3B5044))
    }
}

@Composable
private fun ConceptOption(title: String, description: String, onClick: () -> Unit) {
    HorizontalDivider(color = Color(0xFF3B5044))
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = launcherText, style = MaterialTheme.typography.titleLarge)
            Text(description, color = launcherMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Text("›", color = launcherAccent, style = MaterialTheme.typography.titleLarge)
    }
}
