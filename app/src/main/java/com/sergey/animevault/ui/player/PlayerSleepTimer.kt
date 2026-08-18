package com.sergey.animevault.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultSheetHeader

internal enum class SleepTimerMode(
    val title: String,
    val description: String,
    val durationMs: Long?,
) {
    OFF("Выкл.", "Не останавливать воспроизведение по таймеру", null),
    MINUTES_15("15 минут", "Пауза через 15 минут", 15 * 60_000L),
    MINUTES_30("30 минут", "Пауза через 30 минут", 30 * 60_000L),
    MINUTES_60("60 минут", "Пауза через час", 60 * 60_000L),
    MINUTES_90("90 минут", "Пауза через полтора часа", 90 * 60_000L),
    END_OF_EPISODE("До конца серии", "Остановиться после текущего эпизода", null),
}

internal data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val deadlineMs: Long? = null,
) {
    val active: Boolean get() = mode != SleepTimerMode.OFF

    fun remainingMs(nowMs: Long): Long? = deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }
}

internal fun startSleepTimer(mode: SleepTimerMode, nowMs: Long): SleepTimerState = SleepTimerState(
    mode = mode,
    deadlineMs = mode.durationMs?.let { nowMs + it },
)

internal fun shouldSleepTimerPause(
    state: SleepTimerState,
    nowMs: Long,
    episodeEnded: Boolean = false,
): Boolean = when (state.mode) {
    SleepTimerMode.OFF -> false
    SleepTimerMode.END_OF_EPISODE -> episodeEnded
    else -> state.deadlineMs?.let { nowMs >= it } == true
}

@Composable
internal fun SleepTimerSheet(
    state: SleepTimerState,
    nowMs: () -> Long,
    onSelected: (SleepTimerState) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VaultSheetHeader(
                title = "Таймер сна",
                subtitle = state.remainingMs(nowMs())?.let { "Осталось ${formatSleepTimerRemaining(it)}" }
                    ?: "Таймер относится только к текущему сеансу просмотра.",
            )
            SleepTimerMode.entries.forEach { candidate ->
                val selected = state.mode == candidate
                Surface(
                    onClick = {
                        onSelected(startSleepTimer(candidate, nowMs()))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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

internal fun formatSleepTimerRemaining(milliseconds: Long): String {
    val totalMinutes = (milliseconds.coerceAtLeast(0L) + 59_999L) / 60_000L
    return when {
        totalMinutes >= 60L -> {
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            if (minutes == 0L) "$hours ч" else "$hours ч $minutes мин"
        }
        else -> "$totalMinutes мин"
    }
}
