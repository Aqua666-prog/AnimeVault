package com.sergey.animevault.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class VaultRootNavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun VaultBottomNavigation(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // At accessibility font sizes preserve the complete labels and every destination.
            // The second row costs space deliberately; no text-scale override or hidden tabs.
            val rows = if (maxWidth / LocalDensity.current.fontScale < 260.dp) items.chunked(3) else listOf(items)
            Column(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 4.dp)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { item -> NavigationDestination(item, currentRoute == item.route, onNavigate) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavigationDestination(item: VaultRootNavItem, selected: Boolean, onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Column(Modifier.weight(1f).heightIn(min = 72.dp)
        .selectable(selected, role = Role.Tab, onClick = { if (!selected) onNavigate(item.route) })
        .padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(item.icon, null, tint = tint, modifier = Modifier.background(
            if (selected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp).size(24.dp))
        Text(item.label, color = tint, style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center)
    }
}

@Composable
fun VaultNavigationRail(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationRail(modifier = Modifier.verticalScroll(rememberScrollState()), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        items.forEach { item ->
            val selected = currentRoute == item.route
            NavigationRailItem(selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                modifier = Modifier.padding(vertical = 4.dp),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
