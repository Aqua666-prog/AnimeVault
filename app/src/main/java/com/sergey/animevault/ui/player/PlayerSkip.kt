package com.sergey.animevault.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.components.VaultSheetHeader
import kotlin.math.max

internal data class PlayerSkipSettings(
    val autoSkipOpening: Boolean = false,
    val openingStartMs: Long = 0L,
    val openingEndMs: Long = 0L,
    val autoSkipEnding: Boolean = false,
    val endingStartMs: Long = 0L,
    val endingEndMs: Long = 0L,
) {
    fun normalized(): PlayerSkipSettings = copy(
        openingStartMs = openingStartMs.coerceAtLeast(0L),
        openingEndMs = openingEndMs.coerceAtLeast(0L),
        endingStartMs = endingStartMs.coerceAtLeast(0L),
        endingEndMs = endingEndMs.coerceAtLeast(0L),
    )
}

internal enum class AutoSkipSegment {
    OPENING,
    ENDING,
}

internal data class AutoSkipDecision(
    val segment: AutoSkipSegment,
    val targetMs: Long,
)

internal fun autoSkipDecision(
    settings: PlayerSkipSettings,
    positionMs: Long,
    durationMs: Long,
): AutoSkipDecision? {
    val position = positionMs.coerceAtLeast(0L)
    val duration = durationMs.coerceAtLeast(0L)

    fun decision(
        enabled: Boolean,
        startMs: Long,
        endMs: Long,
        segment: AutoSkipSegment,
    ): AutoSkipDecision? {
        if (!enabled) return null
        val start = startMs.coerceAtLeast(0L)
        val rawEnd = endMs.coerceAtLeast(0L)
        if (rawEnd < start + MIN_SKIP_SEGMENT_MS) return null
        val end = if (duration > 0L) rawEnd.coerceAtMost(duration) else rawEnd
        if (end < start + MIN_SKIP_SEGMENT_MS) return null
        if (position < start || position >= end - SKIP_TARGET_GUARD_MS) return null
        if (end - position < MIN_SKIP_JUMP_MS) return null
        return AutoSkipDecision(segment, end)
    }

    return decision(
        enabled = settings.autoSkipOpening,
        startMs = settings.openingStartMs,
        endMs = settings.openingEndMs,
        segment = AutoSkipSegment.OPENING,
    ) ?: decision(
        enabled = settings.autoSkipEnding,
        startMs = settings.endingStartMs,
        endMs = settings.endingEndMs,
        segment = AutoSkipSegment.ENDING,
    )
}

@Composable
internal fun SkipSettingsSheet(
    settings: PlayerSkipSettings,
    currentPositionMs: () -> Long,
    durationMs: () -> Long,
    onDismiss: () -> Unit,
    onSave: (PlayerSkipSettings) -> Unit,
) {
    var openingEnabled by remember(settings) { mutableStateOf(settings.autoSkipOpening) }
    var openingStart by remember(settings) { mutableStateOf(formatEditableTime(settings.openingStartMs)) }
    var openingEnd by remember(settings) { mutableStateOf(formatEditableTime(settings.openingEndMs)) }
    var endingEnabled by remember(settings) { mutableStateOf(settings.autoSkipEnding) }
    var endingStart by remember(settings) { mutableStateOf(formatEditableTime(settings.endingStartMs)) }
    var endingEnd by remember(settings) { mutableStateOf(formatEditableTime(settings.endingEndMs)) }

    val parsed = PlayerSkipSettings(
        autoSkipOpening = openingEnabled,
        openingStartMs = parsePlayerTimecode(openingStart) ?: -1L,
        openingEndMs = parsePlayerTimecode(openingEnd) ?: -1L,
        autoSkipEnding = endingEnabled,
        endingStartMs = parsePlayerTimecode(endingStart) ?: -1L,
        endingEndMs = parsePlayerTimecode(endingEnd) ?: -1L,
    )
    val validOpening = !openingEnabled || (
        parsed.openingStartMs >= 0L && parsed.openingEndMs >= parsed.openingStartMs + MIN_SKIP_SEGMENT_MS
    )
    val validEnding = !endingEnabled || (
        parsed.endingStartMs >= 0L && parsed.endingEndMs >= parsed.endingStartMs + MIN_SKIP_SEGMENT_MS
    )
    val canSave = validOpening && validEnding

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
        ) {
            VaultSheetHeader(
                title = "Автопропуск",
                subtitle = "Таймкоды сохраняются отдельно для каждого тайтла.",
                modifier = Modifier.padding(bottom = 14.dp),
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Во время просмотра плеер автоматически перепрыгнет заданный диапазон.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SkipSegmentEditor(
                    title = "Опенинг",
                    enabled = openingEnabled,
                    onEnabledChange = { openingEnabled = it },
                    start = openingStart,
                    onStartChange = { openingStart = it },
                    end = openingEnd,
                    onEndChange = { openingEnd = it },
                    onUseCurrentAsStart = { openingStart = formatEditableTime(currentPositionMs()) },
                    onUseCurrentAsEnd = { openingEnd = formatEditableTime(currentPositionMs()) },
                    valid = validOpening,
                )
                SkipSegmentEditor(
                    title = "Эндинг",
                    enabled = endingEnabled,
                    onEnabledChange = { endingEnabled = it },
                    start = endingStart,
                    onStartChange = { endingStart = it },
                    end = endingEnd,
                    onEndChange = { endingEnd = it },
                    onUseCurrentAsStart = { endingStart = formatEditableTime(currentPositionMs()) },
                    onUseCurrentAsEnd = {
                        val duration = durationMs().coerceAtLeast(0L)
                        endingEnd = formatEditableTime(if (duration > 0L) duration else currentPositionMs())
                    },
                    valid = validEnding,
                    endButtonLabel = "Конец серии",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                TextButton(
                    enabled = canSave,
                    onClick = {
                        onSave(parsed.normalized())
                        onDismiss()
                    },
                ) { Text("Сохранить") }
            }
        }
    }
}

@Composable
private fun SkipSegmentEditor(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    start: String,
    onStartChange: (String) -> Unit,
    end: String,
    onEndChange: (String) -> Unit,
    onUseCurrentAsStart: () -> Unit,
    onUseCurrentAsEnd: () -> Unit,
    valid: Boolean,
    endButtonLabel: String = "Сейчас",
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimecodeField(
                value = start,
                onValueChange = onStartChange,
                label = "Начало",
                modifier = Modifier.weight(1f),
            )
            TimecodeField(
                value = end,
                onValueChange = onEndChange,
                label = "Конец",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onUseCurrentAsStart) { Text("Начало = сейчас") }
            TextButton(onClick = onUseCurrentAsEnd) { Text(endButtonLabel) }
        }
        if (!valid) {
            Text(
                "Конец должен быть позже начала хотя бы на одну секунду.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TimecodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(raw.filter { it.isDigit() || it == ':' }.take(MAX_TIMECODE_LENGTH))
        },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("01:30") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
}

internal fun parsePlayerTimecode(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.length > MAX_TIMECODE_LENGTH) return null
    val parts = trimmed.split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any(String::isBlank)) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.any { it < 0L }) return null
    val seconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> {
            if (numbers[1] >= 60L) return null
            numbers[0] * 60L + numbers[1]
        }
        3 -> {
            if (numbers[1] >= 60L || numbers[2] >= 60L) return null
            numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        }
        else -> return null
    }
    return seconds.coerceAtMost(MAX_TIMECODE_SECONDS) * 1_000L
}

internal fun formatEditableTime(milliseconds: Long): String {
    val totalSeconds = max(0L, milliseconds) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private const val MIN_SKIP_SEGMENT_MS = 1_000L
private const val MIN_SKIP_JUMP_MS = 750L
private const val SKIP_TARGET_GUARD_MS = 350L
private const val MAX_TIMECODE_SECONDS = 24L * 60L * 60L
private const val MAX_TIMECODE_LENGTH = 8
