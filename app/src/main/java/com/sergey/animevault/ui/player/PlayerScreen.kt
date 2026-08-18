package com.sergey.animevault.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.sergey.animevault.data.repository.PlaybackBundle
import com.sergey.animevault.ui.components.VaultSheetHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlayerRoute(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlayNext: (Long) -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (val state = uiState) {
        PlayerUiState.Loading -> PlayerMessage { CircularProgressIndicator() }
        is PlayerUiState.Error -> PlayerMessage { Text(state.message, color = Color.White) }
        is PlayerUiState.Ready -> VideoPlayer(
            playback = state.playback,
            onSaveProgress = viewModel::saveProgress,
            onBack = onBack,
            onPlayNext = onPlayNext,
            isInPictureInPictureMode = isInPictureInPictureMode,
            onEnterPictureInPicture = onEnterPictureInPicture,
        )
    }
}

@Composable
private fun VideoPlayer(
    playback: PlaybackBundle,
    onSaveProgress: (Long, Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlayNext: (Long) -> Unit,
    isInPictureInPictureMode: Boolean,
    onEnterPictureInPicture: () -> Boolean,
) {
    val context = LocalContext.current
    val episode = playback.episode
    val preferences = remember(episode.titleId) {
        PlayerPreferences(context, "offline:${episode.titleId}")
    }
    val equalizer = remember(episode.titleId) { PlayerEqualizerController(preferences) }
    var speed by remember(episode.titleId) { mutableFloatStateOf(preferences.speed) }
    var skipSettings by remember(episode.titleId) { mutableStateOf(preferences.skipSettings) }
    var speedMenuVisible by remember { mutableStateOf(false) }
    var equalizerDialogVisible by remember { mutableStateOf(false) }
    var skipDialogVisible by remember { mutableStateOf(false) }
    var tracksMenuVisible by remember { mutableStateOf(false) }
    var scaleMenuVisible by remember { mutableStateOf(false) }
    var nextEpisodeMenuVisible by remember { mutableStateOf(false) }
    var nextEpisodeMode by remember(episode.titleId) { mutableStateOf(preferences.nextEpisodeMode) }
    var pendingNextEpisodeId by remember(episode.id) { mutableStateOf<Long?>(null) }
    var nextEpisodeCountdown by remember(episode.id) { mutableStateOf<Int?>(null) }
    var videoScaleMode by remember(episode.titleId) { mutableStateOf(preferences.videoScaleMode) }
    var endHandled by remember(episode.id) { mutableStateOf(false) }
    var chromeVisible by remember(episode.id) { mutableStateOf(true) }

    val player = remember(episode.id) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                setMediaItem(playback.toMediaItem())
                if (!episode.isCompleted && episode.positionMs > 0L) {
                    seekTo(episode.positionMs)
                }
                setPlaybackSpeed(speed)
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player, playback.nextEpisodeId, nextEpisodeMode) {
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !endHandled) {
                    endHandled = true
                    onSaveProgress(player.currentPosition, player.safeDuration(), true)
                    when (val decision = nextEpisodeDecision(nextEpisodeMode, playback.nextEpisodeId)) {
                        NextEpisodeDecision.Stop -> Unit
                        is NextEpisodeDecision.PlayNow -> onPlayNext(decision.id)
                        is NextEpisodeDecision.Countdown -> pendingNextEpisodeId = decision.id
                    }
                }
            }
        }
        player.addListener(listener)
        equalizer.attach(player.audioSessionId)
        onDispose {
            player.removeListener(listener)
            onSaveProgress(player.currentPosition, player.safeDuration(), false)
            equalizer.release()
            player.release()
        }
    }

    LaunchedEffect(pendingNextEpisodeId) {
        val nextId = pendingNextEpisodeId ?: return@LaunchedEffect
        for (remaining in NEXT_EPISODE_COUNTDOWN_SECONDS downTo 1) {
            if (pendingNextEpisodeId != nextId) return@LaunchedEffect
            nextEpisodeCountdown = remaining
            delay(1_000L)
        }
        if (pendingNextEpisodeId == nextId) {
            pendingNextEpisodeId = null
            nextEpisodeCountdown = null
            onPlayNext(nextId)
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            delay(5_000)
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
                onSaveProgress(player.currentPosition, player.safeDuration(), false)
            }
        }
    }

    PlayerStopEffect(player) {
        if (!endHandled) {
            onSaveProgress(player.currentPosition, player.safeDuration(), false)
        }
    }

    LaunchedEffect(player, skipSettings) {
        while (isActive) {
            delay(AUTO_SKIP_POLL_MS)
            if (!player.isPlaying) continue
            autoSkipDecision(
                settings = skipSettings,
                positionMs = player.currentPosition,
                durationMs = player.safeDuration(),
            )?.let { decision ->
                player.seekTo(decision.targetMs)
            }
        }
    }

    LaunchedEffect(
        chromeVisible,
        speedMenuVisible,
        equalizerDialogVisible,
        skipDialogVisible,
        tracksMenuVisible,
        scaleMenuVisible,
        nextEpisodeMenuVisible,
    ) {
        if (chromeVisible && player.isPlaying &&
            !speedMenuVisible && !equalizerDialogVisible &&
            !skipDialogVisible && !tracksMenuVisible &&
            !scaleMenuVisible && !nextEpisodeMenuVisible
        ) {
            delay(4_500L)
            if (player.isPlaying) {
                chromeVisible = false
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val isLandscape = maxWidth > maxHeight
        PlayerSurface(
            modifier = Modifier.fillMaxSize(),
            player = player,
            showController = false,
            previewUri = episode.fileUri.toUri(),
            videoScaleMode = videoScaleMode,
            onSingleTap = { chromeVisible = !chromeVisible },
            onPinchScale = { direction ->
                videoScaleMode = videoScaleMode.step(direction)
                preferences.videoScaleMode = videoScaleMode
                chromeVisible = true
            },
        )

        AnimatedVisibility(
            visible = !isInPictureInPictureMode && chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
            PlayerChromeScrims()
            PlayerChromeButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Назад",
                onClick = {
                    onSaveProgress(player.currentPosition, player.safeDuration(), false)
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )

            PlayerChromeDock(
                landscape = isLandscape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                PlayerChromeButton(
                    icon = Icons.Outlined.AspectRatio,
                    contentDescription = "Масштаб видео",
                    onClick = { scaleMenuVisible = true },
                    active = videoScaleMode != VideoScaleMode.FIT,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Subtitles,
                    contentDescription = "Аудио и субтитры",
                    onClick = { tracksMenuVisible = true },
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Speed,
                    contentDescription = "Скорость",
                    onClick = { speedMenuVisible = true },
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "Следующая серия",
                    onClick = { nextEpisodeMenuVisible = true },
                    active = nextEpisodeMode != NextEpisodeMode.OFF,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Timer,
                    contentDescription = "Автопропуск",
                    onClick = { skipDialogVisible = true },
                    active = skipSettings.autoSkipOpening || skipSettings.autoSkipEnding,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.GraphicEq,
                    contentDescription = "Эквалайзер",
                    onClick = { equalizerDialogVisible = true },
                )
                if (isPlayerPictureInPictureSupported(context)) {
                    PlayerChromeButton(
                        icon = Icons.Outlined.PictureInPictureAlt,
                        contentDescription = "Картинка в картинке",
                        onClick = { onEnterPictureInPicture() },
                    )
                }
            }

            PlayerTransportControls(
                player = player,
                modifier = Modifier.align(Alignment.Center),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .widthIn(max = 980.dp)
                    .fillMaxWidth(),
            ) {
                PlayerNowPlayingBar(
                    title = episode.titleName,
                    subtitle = buildString {
                        episode.seasonNumber?.let { append("Сезон $it · ") }
                        episode.episodeNumber?.let { append("Серия ${it.toDisplayEpisodeNumber()} · ") }
                        append(episode.fileName)
                    },
                    modifier = Modifier.widthIn(max = if (isLandscape) 560.dp else 440.dp),
                )
                Spacer(Modifier.padding(top = 3.dp))
                PlayerTimeline(
                    player = player,
                    skipSettings = skipSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }
        }
        val pendingId = pendingNextEpisodeId
        val countdown = nextEpisodeCountdown
        if (!isInPictureInPictureMode && pendingId != null && countdown != null) {
            NextEpisodeCountdownOverlay(
                seconds = countdown,
                onCancel = {
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                },
                onPlayNow = {
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                    onPlayNext(pendingId)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .widthIn(max = 360.dp),
            )
        }
    }

    if (nextEpisodeMenuVisible && !isInPictureInPictureMode) {
        NextEpisodeModeSheet(
            mode = nextEpisodeMode,
            onModeSelected = { mode ->
                nextEpisodeMode = mode
                preferences.nextEpisodeMode = mode
                if (mode == NextEpisodeMode.OFF) {
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                }
                chromeVisible = true
            },
            onDismiss = { nextEpisodeMenuVisible = false },
        )
    }

    if (speedMenuVisible && !isInPictureInPictureMode) {
        ModalBottomSheet(
            onDismissRequest = { speedMenuVisible = false },
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
                    title = "Скорость воспроизведения",
                    subtitle = "Настройка запоминается для этого локального тайтла.",
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                listOf(
                    listOf(0.5f, 0.75f, 1f, 1.25f),
                    listOf(1.5f, 1.75f, 2f),
                ).forEach { rowValues ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 9.dp),
                    ) {
                        rowValues.forEachIndexed { index, value ->
                            val selected = value == speed
                            Surface(
                                onClick = {
                                    speed = value
                                    preferences.speed = value
                                    player.setPlaybackSpeed(value)
                                    speedMenuVisible = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = if (index == rowValues.lastIndex) 0.dp else 8.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
                                },
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)
                                    },
                                ),
                            ) {
                                Text(
                                    text = if (value == 1f) "1× · норма" else "${value}×",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 13.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        repeat(4 - rowValues.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (tracksMenuVisible && !isInPictureInPictureMode) {
        PlayerTracksSheet(
            player = player,
            onDismiss = { tracksMenuVisible = false },
        )
    }
    if (scaleMenuVisible && !isInPictureInPictureMode) {
        VideoScaleModeSheet(
            selected = videoScaleMode,
            onSelected = { mode ->
                videoScaleMode = mode
                preferences.videoScaleMode = mode
                chromeVisible = true
            },
            onDismiss = { scaleMenuVisible = false },
        )
    }

    if (equalizerDialogVisible && !isInPictureInPictureMode) {
        EqualizerDialog(
            controller = equalizer,
            onDismiss = { equalizerDialogVisible = false },
        )
    }
    if (skipDialogVisible && !isInPictureInPictureMode) {
        SkipSettingsDialog(
            settings = skipSettings,
            currentPositionMs = { player.currentPosition.coerceAtLeast(0L) },
            durationMs = player::safeDuration,
            onDismiss = { skipDialogVisible = false },
            onSave = { updated ->
                skipSettings = updated
                preferences.skipSettings = updated
            },
        )
    }
}

@Composable
private fun PlayerActionButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.38f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick) { icon() }
    }
}

private fun PlaybackBundle.toMediaItem(): MediaItem {
    val subtitleConfigurations = subtitles.mapIndexed { index, subtitle ->
        MediaItem.SubtitleConfiguration.Builder(subtitle.fileUri.toUri())
            .setMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.fileName)
            .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
    return MediaItem.Builder()
        .setUri(episode.fileUri.toUri())
        .setMediaId(episode.id.toString())
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()
}

private fun Player.safeDuration(): Long = duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L

private fun Double.toDisplayEpisodeNumber(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

@Composable
private fun PlayerMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private const val AUTO_SKIP_POLL_MS = 300L
