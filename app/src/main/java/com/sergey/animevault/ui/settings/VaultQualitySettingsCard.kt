package com.sergey.animevault.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.theme.VaultLemon
import com.sergey.animevault.ui.theme.VaultViolet

private val qualityPresets: List<Pair<Int?, String>> = listOf(
    null to "Авто", 480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K",
)

@Composable
internal fun VaultQualitySettingsCard(
    selected: Int?,
    title: String,
    subtitle: String,
    onSelect: (Int?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(
                    Brush.horizontalGradient(listOf(VaultViolet.copy(alpha = 0.28f), VaultLemon.copy(alpha = 0.08f))),
                ).padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            ) {
                items(qualityPresets) { (quality, label) ->
                    FilterChip(selected = quality == selected, onClick = { onSelect(quality) },
                        label = { Text(label, fontWeight = FontWeight.SemiBold) })
                }
            }
            Text("Доступность 2K/4K зависит от видеопотока и декодера устройства.",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
