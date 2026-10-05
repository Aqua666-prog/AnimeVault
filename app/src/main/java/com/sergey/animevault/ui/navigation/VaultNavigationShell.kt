package com.sergey.animevault.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sergey.animevault.ui.theme.LocalVaultColors

data class VaultRootNavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun VaultBottomNavigation(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalVaultColors.current
    Surface(color = colors.surface.copy(alpha = 0.96f), tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val tint = if (selected) brand.action else colors.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 68.dp)
                        .selectable(selected, role = Role.Tab, onClick = { if (!selected) onNavigate(item.route) })
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint,
                        modifier = Modifier.size(24.dp).background(
                            if (selected) brand.navigation.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        ))
                    Text(item.label, color = tint, fontSize = 10.sp, lineHeight = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun VaultNavigationRail(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    val brand = LocalVaultColors.current
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEach { item ->
            val selected = currentRoute == item.route
            NavigationRailItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
                modifier = Modifier.padding(vertical = 4.dp),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = brand.action,
                    selectedTextColor = brand.action,
                    indicatorColor = brand.navigation.copy(alpha = 0.12f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
