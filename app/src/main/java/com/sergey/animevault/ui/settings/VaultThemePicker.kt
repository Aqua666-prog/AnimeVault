package com.sergey.animevault.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.design.RaidenEmblem
import com.sergey.animevault.ui.design.raidenEdge
import com.sergey.animevault.ui.preferences.*
import com.sergey.animevault.ui.theme.AnimeVaultTheme

@Composable
fun VaultThemePicker(selected: VaultThemeMode, onSelect: (VaultThemeMode) -> Unit) {
    val primary = listOf(VaultThemeMode.VAULT, VaultThemeMode.EUTHYMIA, VaultThemeMode.MUSOU)
    var more by remember { mutableStateOf(selected !in primary) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Тема AnimeVault", style = MaterialTheme.typography.titleLarge)
        Text("Предпросмотр · применяется сразу", style = MaterialTheme.typography.bodySmall)
        (if (more) VaultThemeMode.entries.toList() else primary).forEach { mode ->
            AnimeVaultTheme(AppearanceSettings(theme = mode, motion = VaultMotionMode.OFF)) {
                val c = MaterialTheme.colorScheme
                Surface(color = c.surfaceContainer, shape = MaterialTheme.shapes.large,
                    border = BorderStroke(if (selected == mode) 2.dp else 1.dp, if (selected == mode) c.primary else c.outlineVariant),
                    modifier = Modifier.fillMaxWidth().selectable(selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })) {
                    Column(Modifier.raidenEdge().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (mode.isRaiden) RaidenEmblem(Modifier.size(36.dp))
                            Text(mode.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (selected == mode) Icon(Icons.Outlined.CheckCircle, "Выбрано", tint = c.primary)
                        }
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(c.primary, c.secondary, c.background, c.surfaceContainerHigh).forEach {
                                Box(Modifier.size(40.dp, 8.dp).background(it, MaterialTheme.shapes.small))
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { more = !more }) { Text(if (more) "Скрыть дополнительные палитры" else "Midnight, AMOLED и Dynamic") }
    }
}
