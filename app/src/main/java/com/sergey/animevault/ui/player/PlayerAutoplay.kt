package com.sergey.animevault.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultSheetHeader

internal enum class NextEpisodeMode(val title: String, val description: String) {
    OFF("Выкл.", "Остановиться после титров"),
    COUNTDOWN("С отсчётом", "Показать карточку и перейти через несколько секунд"),
    IMMEDIATE("Сразу", "Открыть следующую серию без паузы"),
}

internal sealed interface NextEpisodeDecision<out T> {
    data object Stop : NextEpisodeDecision<Nothing>
    data class PlayNow<T>(val id: T) : NextEpisodeDecision<T>
    data class Countdown<T>(val id: T) : NextEpisodeDecision<T>
}

internal fun <T> nextEpisodeDecision(
    mode: NextEpisodeMode,
    nextEpisodeId: T?,
): NextEpisodeDecision<T> {
    val id = nextEpisodeId ?: return NextEpisodeDecision.Stop
    return when (mode) {
        NextEpisodeMode.OFF -> NextEpisodeDecision.Stop
        NextEpisodeMode.IMMEDIATE -> NextEpisodeDecision.PlayNow(id)
        NextEpisodeMode.COUNTDOWN -> NextEpisodeDecision.Countdown(id)
    }
}

@Composable
internal fun NextEpisodeModeSheet(
    mode: NextEpisodeMode,
    onModeSelected: (NextEpisodeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VaultSheetHeader(
                title = "Следующая серия",
                subtitle = "Поведение после полного завершения эпизода.",
            )
            NextEpisodeMode.entries.forEach { candidate ->
                Surface(
                    onClick = {
                        onModeSelected(candidate)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (mode == candidate) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (mode == candidate) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (mode == candidate) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                            },
                            contentColor = if (mode == candidate) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ) {
                            Text(
                                text = candidate.title,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(candidate.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                candidate.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NextEpisodeCountdownOverlay(
    seconds: Int,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.80f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.SkipNext, contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("Следующая серия", fontWeight = FontWeight.Bold)
                    Text(
                        "Запуск через ${seconds.coerceAtLeast(0)} с",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Остаться") }
                Button(onClick = onPlayNow, modifier = Modifier.weight(1f)) { Text("Смотреть") }
            }
        }
    }
}

internal const val NEXT_EPISODE_COUNTDOWN_SECONDS = 8
