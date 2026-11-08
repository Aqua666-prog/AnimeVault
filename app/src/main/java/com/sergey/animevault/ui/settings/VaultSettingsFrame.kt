package com.sergey.animevault.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.AnimeBrandTitle
import com.sergey.animevault.ui.components.VaultTopBarAction

/** Opaque, content-sized chrome. Top insets belong to the bar; bottom/side to content. */
@Composable
internal fun VaultSettingsFrame(
    subtitle: String,
    onBack: () -> Unit,
    backDescription: String,
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Row(Modifier.fillMaxWidth()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    VaultTopBarAction(Icons.AutoMirrored.Outlined.ArrowBack, backDescription, onBack)
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { AnimeBrandTitle(subtitle) }
                }
            }
        }, content = content,
    )
}
