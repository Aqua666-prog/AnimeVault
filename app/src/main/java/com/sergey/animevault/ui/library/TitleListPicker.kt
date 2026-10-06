package com.sergey.animevault.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.ui.preferences.VaultTitleList

@Composable
fun VaultTitleListPicker(
    current: VaultTitleList,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSelect: (VaultTitleList) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            VaultSheetHeader("Ваша библиотека", modifier = Modifier.padding(bottom = 16.dp))
            Text(if (favorite) "✓ В избранном · убрать" else "+ Добавить в избранное",
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggleFavorite(); onDismiss() }
                    .padding(vertical = 14.dp), style = MaterialTheme.typography.titleSmall)
            VaultTitleList.entries.forEach { list ->
                Text((if (current == list) "✓ " else "") + list.title,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(list); onDismiss() }
                        .padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge,
                    color = if (current == list) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
