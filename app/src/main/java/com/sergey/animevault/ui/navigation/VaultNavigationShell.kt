package com.sergey.animevault.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.sergey.animevault.ui.design.electroGlow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.design.VaultSize
import com.sergey.animevault.ui.theme.LocalVaultColors

data class VaultRootNavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun VaultBottomNavigation(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalVaultColors.current
    Surface(
        color = colors.background,
        tonalElevation = 0.dp,
        modifier = Modifier.drawWithCache {
            val edge = colors.outlineVariant.copy(alpha = .55f)
            val width = VaultSize.hairline.toPx()
            onDrawWithContent {
                drawContent()
                drawLine(edge, Offset(0f, width / 2), Offset(size.width, width / 2), width)
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                val tint = if (selected) colors.primary else colors.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                        .testTag("vault-route-${item.route}")
                        .selectable(selected, role = Role.Tab, onClick = { if (!selected) onNavigate(item.route) })
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.width(24.dp).height(3.dp).background(
                        if (selected) brand.navigation else Color.Transparent,
                        RoundedCornerShape(50),
                    ))
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    // Keep labels in the semantics tree and permit additional lines at large font scales.
                    Text(item.label, color = tint, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun VaultNavigationRail(items: List<VaultRootNavItem>, currentRoute: String?, onNavigate: (String) -> Unit) {
    val brand = LocalVaultColors.current
    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
        items.forEach { item ->
            val selected = currentRoute == item.route
            NavigationRailItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
                modifier = Modifier.padding(vertical = 4.dp).electroGlow(selected).testTag("vault-route-${item.route}"),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = brand.navigation.copy(alpha = 0.18f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
