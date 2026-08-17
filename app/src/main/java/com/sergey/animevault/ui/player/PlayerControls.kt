package com.sergey.animevault.ui.player

import kotlin.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.sergey.animevault.ui.components.VaultSheetHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

internal enum class VideoScaleMode(
    val label: String,
    val shortLabel: String,
) {
    FIT("Вписать", "Fit"),
    FILL("Заполнить", "Fill"),
    ZOOM("Увеличить", "Zoom");

    fun step(direction: Int): VideoScaleMode {
        val values = entries
        val next = (ordinal + if (direction >= 0) 1 else -1 + values.size) % values.size
        return values[next]
    }
}

internal data class PlayerUiSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSeekable: Boolean = false,
)

@Composable
internal fun rememberPlayerUiSnapshot(
    player: Player,
    fallbackDurationMs: Long = 0L,
): PlayerUiSnapshot {
    var snapshot by remember(player) { mutableStateOf(PlayerUiSnapshot()) }
    LaunchedEffect(player, fallbackDurationMs) {
        while (isActive) {
            val duration = player.duration
                .takeIf { it > 0L && it != C.TIME_UNSET }
                ?: fallbackDurationMs.coerceAtLeast(0L)
            snapshot = PlayerUiSnapshot(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
                isPlaying = player.isPlaying,
                isSeekable = player.isCurrentMediaItemSeekable,
            )
            delay(250L)
        }
    }
    return snapshot
}

@Composable
internal fun PlayerTransportControls(
    player: Player,
    modifier: Modifier = Modifier,
) {
    val snapshot = rememberPlayerUiSnapshot(player)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerTransportButton(
            icon = Icons.Outlined.Replay10,
            description = "Назад на 10 секунд",
            enabled = snapshot.isSeekable,
            onClick = { player.seekBack() },
        )
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.94f),
            contentColor = Color.Black,
            shadowElevation = 8.dp,
        ) {
            IconButton(
                onClick = {
                    if (snapshot.isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.size(68.dp),
            ) {
                Icon(
                    imageVector = if (snapshot.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Пауза" else "Воспроизвести",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        PlayerTransportButton(
            icon = Icons.Outlined.FastForward,
            description = "Вперёд на 15 секунд",
            enabled = snapshot.isSeekable,
            onClick = { player.seekForward() },
        )
    }
}

@Composable
private fun PlayerTransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        contentColor = Color.White,
    ) {
        IconButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.32f),
            )
        }
    }
}

@Composable
internal fun PlayerTimeline(
    player: Player,
    skipSettings: PlayerSkipSettings,
    modifier: Modifier = Modifier,
    fallbackDurationMs: Long = 0L,
) {
    val snapshot = rememberPlayerUiSnapshot(player, fallbackDurationMs)
    var dragging by remember(player) { mutableStateOf(false) }
    var dragPositionMs by remember(player) { mutableLongStateOf(0L) }
    val duration = snapshot.durationMs.coerceAtLeast(1L)
    val displayedPosition = if (dragging) dragPositionMs else snapshot.positionMs.coerceIn(0L, duration)
    val progress = displayedPosition.toFloat() / duration.toFloat()
    val buffered = snapshot.bufferedPositionMs.coerceIn(0L, duration).toFloat() / duration.toFloat()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            val inactive = Color.White.copy(alpha = 0.18f)
            val bufferedColor = Color.White.copy(alpha = 0.32f)
            val active = MaterialTheme.colorScheme.primary
            val opening = MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f)
            val ending = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.88f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
            ) {
                val y = size.height / 2f
                val railHeight = 4.dp.toPx()
                drawRoundRect(
                    color = inactive,
                    topLeft = Offset(0f, y - railHeight / 2f),
                    size = Size(size.width, railHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(railHeight, railHeight),
                )
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, y - railHeight / 2f),
                    size = Size(size.width * buffered.coerceIn(0f, 1f), railHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(railHeight, railHeight),
                )
                fun segment(startMs: Long, endMs: Long, color: Color) {
                    if (startMs < 0L || endMs <= startMs || duration <= 0L) return
                    val start = (startMs.toFloat() / duration).coerceIn(0f, 1f)
                    val end = (endMs.toFloat() / duration).coerceIn(0f, 1f)
                    if (end <= start) return
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(size.width * start, y - 4.dp.toPx()),
                        size = Size(size.width * (end - start), 8.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                }
                segment(skipSettings.openingStartMs, skipSettings.openingEndMs, opening)
                segment(skipSettings.endingStartMs, skipSettings.endingEndMs, ending)
                drawRoundRect(
                    color = active,
                    topLeft = Offset(0f, y - 2.dp.toPx()),
                    size = Size(size.width * progress.coerceIn(0f, 1f), 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
            Slider(
                value = displayedPosition.toFloat(),
                enabled = snapshot.isSeekable && snapshot.durationMs > 0L,
                onValueChange = { value ->
                    dragging = true
                    dragPositionMs = value.toLong().coerceIn(0L, duration)
                },
                onValueChangeFinished = {
                    if (dragging) player.seekTo(dragPositionMs)
                    dragging = false
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledThumbColor = Color.White.copy(alpha = 0.35f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatZenithTime(displayedPosition),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (skipSettings.openingEndMs > skipSettings.openingStartMs) {
                    TimelineLegend("OP", MaterialTheme.colorScheme.secondary)
                }
                if (skipSettings.endingEndMs > skipSettings.endingStartMs) {
                    TimelineLegend("ED", MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(
                text = if (snapshot.durationMs > 0L) formatZenithTime(snapshot.durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
            )
        }
    }
}

@Composable
private fun TimelineLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(6.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
    }
}

@Composable
internal fun VideoScaleModeSheet(
    selected: VideoScaleMode,
    onSelected: (VideoScaleMode) -> Unit,
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
        ) {
            VaultSheetHeader(
                title = "Масштаб видео",
                subtitle = "Жест щипка на видео также переключает режимы.",
                modifier = Modifier.padding(bottom = 14.dp),
            )
            VideoScaleMode.entries.forEach { mode ->
                Surface(
                    onClick = {
                        onSelected(mode)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = if (mode == selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (mode == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (mode) {
                                    VideoScaleMode.FIT -> "Всё изображение без обрезки"
                                    VideoScaleMode.FILL -> "Растянуть до границ экрана; пропорции могут измениться"
                                    VideoScaleMode.ZOOM -> "Крупный кадр с обрезкой краёв"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (mode == selected) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerTracksSheet(
    player: Player,
    onDismiss: () -> Unit,
) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val audioChoices = remember(tracks) { collectTrackChoices(tracks, C.TRACK_TYPE_AUDIO) }
    val textChoices = remember(tracks) { collectTrackChoices(tracks, C.TRACK_TYPE_TEXT) }

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
                title = "Аудио и субтитры",
                subtitle = "Дорожки берутся напрямую из текущего файла или HLS-потока.",
                modifier = Modifier.padding(bottom = 14.dp),
            )
            if (audioChoices.isNotEmpty()) {
                Text("Аудио", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                audioChoices.forEach { choice ->
                    TrackChoiceRow(choice) {
                        selectTrack(player, choice)
                        tracks = player.currentTracks
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Субтитры", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Surface(
                onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    tracks = player.currentTracks
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 7.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ) {
                Text("Без субтитров", modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            }
            textChoices.forEach { choice ->
                TrackChoiceRow(choice) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndex),
                        )
                        .build()
                    tracks = player.currentTracks
                }
            }
            if (audioChoices.isEmpty() && textChoices.isEmpty()) {
                Text(
                    "Дополнительные дорожки пока не обнаружены. Они появятся здесь после подготовки медиа, если источник их содержит.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class TrackChoice(
    val group: Tracks.Group,
    val trackIndex: Int,
    val type: Int,
    val label: String,
    val detail: String,
    val selected: Boolean,
)

@OptIn(UnstableApi::class)
private fun collectTrackChoices(tracks: Tracks, type: Int): List<TrackChoice> = buildList {
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            add(
                TrackChoice(
                    group = group,
                    trackIndex = index,
                    type = type,
                    label = trackPrimaryLabel(format, type, size + 1),
                    detail = trackDetail(format, type),
                    selected = group.isTrackSelected(index),
                ),
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun selectTrack(player: Player, choice: TrackChoice) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(choice.type, false)
        .setOverrideForType(
            TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndex),
        )
        .build()
}

@Composable
private fun TrackChoiceRow(choice: TrackChoice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp),
        shape = RoundedCornerShape(15.dp),
        color = if (choice.selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        },
        border = BorderStroke(
            1.dp,
            if (choice.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
            else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(choice.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (choice.detail.isNotBlank()) {
                    Text(
                        choice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (choice.selected) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun trackPrimaryLabel(format: Format, type: Int, ordinal: Int): String {
    val label = format.label?.trim().orEmpty()
    if (label.isNotBlank()) return label
    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).displayLanguage.takeIf(String::isNotBlank) }
    if (!language.isNullOrBlank()) return language.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return when (type) {
        C.TRACK_TYPE_AUDIO -> "Аудиодорожка $ordinal"
        C.TRACK_TYPE_TEXT -> "Субтитры $ordinal"
        else -> "Дорожка $ordinal"
    }
}

private fun trackDetail(format: Format, type: Int): String = buildList {
    format.codecs?.takeIf(String::isNotBlank)?.let(::add)
    if (type == C.TRACK_TYPE_AUDIO) {
        if (format.channelCount > 0) add("${format.channelCount} ch")
        if (format.sampleRate > 0) add("${format.sampleRate / 1000f} kHz")
    }
    if (format.bitrate > 0) add("${format.bitrate / 1000} kbps")
}.joinToString(" · ")

private fun formatZenithTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
