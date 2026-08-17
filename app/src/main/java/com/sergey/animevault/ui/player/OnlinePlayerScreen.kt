package com.sergey.animevault.ui.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.sergey.animevault.BuildConfig
import kotlin.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.kodik.KODIK_USER_AGENT
import com.sergey.animevault.data.kodik.KodikStreamResolver
import com.sergey.animevault.data.kodik.normalizeHttpsUrl
import com.sergey.animevault.ui.online.OnlinePlaybackBundle
import com.sergey.animevault.ui.online.OnlinePlayerUiState
import com.sergey.animevault.ui.online.OnlinePlayerViewModel
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
fun OnlinePlayerRoute(
    viewModel: OnlinePlayerViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when (val state = uiState) {
        OnlinePlayerUiState.Loading -> OnlinePlayerMessage { CircularProgressIndicator() }
        is OnlinePlayerUiState.Error -> {
            LaunchedEffect(state.message) {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            OnlinePlayerMessage {
                Text(state.message, color = Color.White)
            }
        }
        is OnlinePlayerUiState.Ready -> OnlineVideoPlayer(
            playback = state.playback,
            onSaveProgress = viewModel::saveProgress,
            onSelectStream = viewModel::selectStream,
            onBack = onBack,
            onPlayEpisode = onPlayEpisode,
            isInPictureInPictureMode = isInPictureInPictureMode,
            onEnterPictureInPicture = onEnterPictureInPicture,
        )
    }
}

/**
 * Режим прямого запуска из Intent: готовый m3u8 играет сразу, а ссылка Kodik
 * сначала преобразуется в набор HLS-потоков 360/480/720p.
 */
@Composable
internal fun DirectPlayerRoute(
    request: DirectPlaybackRequest,
    onBack: () -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val resolver = remember(request.userAgent) {
        KodikStreamResolver(userAgent = request.userAgent ?: KODIK_USER_AGENT)
    }
    val state by produceState<DirectPlayerState>(
        initialValue = DirectPlayerState.Loading,
        request,
        resolver,
    ) {
        value = runCatchingCancellable {
            val template = OnlineStream(
                id = "direct",
                quality = request.quality,
                url = request.m3u8Url ?: request.kodikLink.orEmpty(),
                type = if (request.m3u8Url != null) OnlineStreamType.HLS else OnlineStreamType.EMBED,
                headers = buildMap {
                    put("User-Agent", request.userAgent ?: KODIK_USER_AGENT)
                    request.referer?.let { put("Referer", it) }
                },
                translation = request.voice,
                sourceName = if (request.m3u8Url != null) "Прямая ссылка" else "Kodik",
            )
            val streams = if (request.m3u8Url != null) {
                val hlsUrl = normalizeHttpsUrl(request.m3u8Url)
                    ?: error("Некорректная или незащищённая ссылка HLS")
                listOf(template.copy(url = hlsUrl))
            } else {
                resolver.resolve(template)
            }
            check(streams.isNotEmpty()) { "Источник не вернул доступных потоков" }
            val directEpisode = OnlineEpisode(
                providerId = "direct",
                id = "direct",
                releaseId = "direct",
                ordinal = null,
                name = request.voice,
                previewUrl = null,
                durationMs = 0L,
                sortOrder = 0.0,
                streams = streams,
            )
            val directProgress = OnlineWatchProgress()
            OnlinePlaybackBundle(
                providerId = "direct",
                providerName = if (request.m3u8Url != null) "Прямая ссылка" else "Kodik",
                releaseId = "direct",
                releaseName = request.title,
                episode = directEpisode,
                episodes = listOf(directEpisode),
                progress = directProgress,
                episodeProgress = mapOf(directEpisode.id to directProgress),
                nextEpisodeId = null,
            )
        }.fold(
            onSuccess = DirectPlayerState::Ready,
            onFailure = {
                Log.e(PLAYER_LOG_TAG, "Ошибка подготовки прямого потока", it)
                DirectPlayerState.Error(it.message ?: "Не удалось подготовить поток")
            },
        )
    }

    when (val current = state) {
        DirectPlayerState.Loading -> OnlinePlayerMessage { CircularProgressIndicator() }
        is DirectPlayerState.Error -> {
            LaunchedEffect(current.message) {
                Toast.makeText(context, current.message, Toast.LENGTH_LONG).show()
            }
            OnlinePlayerMessage { Text(current.message, color = Color.White) }
        }
        is DirectPlayerState.Ready -> OnlineVideoPlayer(
            playback = current.playback,
            onSaveProgress = { _, _, _ -> },
            onSelectStream = {},
            onBack = onBack,
            onPlayEpisode = {},
            isInPictureInPictureMode = isInPictureInPictureMode,
            onEnterPictureInPicture = onEnterPictureInPicture,
        )
    }
}

private sealed interface DirectPlayerState {
    data object Loading : DirectPlayerState
    data class Ready(val playback: OnlinePlaybackBundle) : DirectPlayerState
    data class Error(val message: String) : DirectPlayerState
}

@Composable
private fun OnlineVideoPlayer(
    playback: OnlinePlaybackBundle,
    onSaveProgress: (Long, Long, Boolean) -> Unit,
    onSelectStream: (OnlineStream) -> Unit,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    isInPictureInPictureMode: Boolean,
    onEnterPictureInPicture: () -> Boolean,
) {
    val episode = playback.episode
    val context = LocalContext.current
    val preferenceTitleKey = if (playback.providerId == "direct") {
        "online:direct:${playback.releaseName}"
    } else {
        "online:${playback.providerId}:${playback.releaseId}"
    }
    val preferences = remember(preferenceTitleKey) {
        PlayerPreferences(context, preferenceTitleKey)
    }
    val equalizer = remember(preferenceTitleKey) {
        PlayerEqualizerController(preferences)
    }
    var selectedStream by remember(episode.id) {
        mutableStateOf(
            selectPreferredOnlineStream(
                streams = episode.streams,
                translation = preferences.preferredTranslation,
                quality = preferences.preferredQuality,
                sourceName = preferences.preferredSourceName,
            ),
        )
    }
    var qualityMenuVisible by remember { mutableStateOf(false) }
    var speedMenuVisible by remember { mutableStateOf(false) }
    var equalizerDialogVisible by remember { mutableStateOf(false) }
    var skipDialogVisible by remember { mutableStateOf(false) }
    var tracksMenuVisible by remember { mutableStateOf(false) }
    var scaleMenuVisible by remember { mutableStateOf(false) }
    var episodePickerVisible by remember(episode.id) { mutableStateOf(false) }
    var skipSettings by remember(preferenceTitleKey) { mutableStateOf(preferences.skipSettings) }
    var speed by remember(preferenceTitleKey) {
        mutableFloatStateOf(preferences.speed)
    }
    var resumePosition by remember(episode.id) {
        mutableLongStateOf(if (playback.progress.isCompleted) 0L else playback.progress.positionMs)
    }
    var playbackError by remember(episode.id) { mutableStateOf<String?>(null) }
    var failedStreamKeys by remember(episode.id) { mutableStateOf(emptySet<String>()) }
    var webLoading by remember(selectedStream.id) { mutableStateOf(false) }
    var isMarkedWatched by remember(episode.id) { mutableStateOf(playback.progress.isCompleted) }
    var chromeVisible by remember(episode.id) { mutableStateOf(true) }
    var videoScaleMode by remember(preferenceTitleKey) { mutableStateOf(preferences.videoScaleMode) }
    var nativePlayer by remember(episode.id, selectedStream.id) { mutableStateOf<Player?>(null) }
    LaunchedEffect(playbackError) {
        playbackError?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(selectedStream.type) {
        if (selectedStream.type == OnlineStreamType.EMBED) skipDialogVisible = false
    }

    LaunchedEffect(nativePlayer, tracksMenuVisible) {
        if (tracksMenuVisible && nativePlayer == null) tracksMenuVisible = false
    }

    LaunchedEffect(
        chromeVisible,
        speedMenuVisible,
        equalizerDialogVisible,
        skipDialogVisible,
        qualityMenuVisible,
        episodePickerVisible,
        tracksMenuVisible,
        scaleMenuVisible,
    ) {
        if (chromeVisible && !speedMenuVisible && !equalizerDialogVisible && !skipDialogVisible &&
            !qualityMenuVisible && !episodePickerVisible && !tracksMenuVisible && !scaleMenuVisible
        ) {
            delay(4_500L)
            chromeVisible = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val isLandscape = maxWidth > maxHeight
        if (selectedStream.type == OnlineStreamType.EMBED) {
            EmbeddedOnlinePlayer(
                stream = selectedStream,
                onLoadingChanged = { webLoading = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (webLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            NativeOnlinePlayer(
                playback = playback,
                stream = selectedStream,
                initialPositionMs = resumePosition,
                speed = speed,
                equalizer = equalizer,
                onPositionSaved = { position, duration, ended ->
                    resumePosition = if (ended) 0L else position
                    onSaveProgress(position, duration, ended)
                },
                onEnded = { playback.nextEpisodeId?.let(onPlayEpisode) },
                skipSettings = skipSettings,
                showSkipDialog = skipDialogVisible,
                onDismissSkipDialog = { skipDialogVisible = false },
                onSkipSettingsChanged = { updated ->
                    skipSettings = updated
                    preferences.skipSettings = updated
                },
                onError = { message ->
                    val failed = failedStreamKeys + selectedStream.failureKey()
                    val fallback = selectFallbackStream(
                        streams = episode.streams,
                        current = selectedStream,
                        failedStreamKeys = failed,
                    )
                    failedStreamKeys = failed
                    if (fallback != null) {
                        Toast.makeText(
                            context,
                            "${selectedStream.displayName} не отвечает. Пробую ${fallback.displayName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        selectedStream = fallback
                        onSelectStream(fallback)
                        playbackError = null
                    } else {
                        playbackError = message
                    }
                },
                videoScaleMode = videoScaleMode,
                onSingleTap = { chromeVisible = !chromeVisible },
                onPinchScale = { direction ->
                    videoScaleMode = videoScaleMode.step(direction)
                    preferences.videoScaleMode = videoScaleMode
                    chromeVisible = true
                },
                onPlayerAvailable = { nativePlayer = it },
                isInPictureInPictureMode = isInPictureInPictureMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = !isInPictureInPictureMode && (
                selectedStream.type == OnlineStreamType.EMBED ||
                    chromeVisible ||
                    speedMenuVisible ||
                    qualityMenuVisible ||
                    episodePickerVisible ||
                    tracksMenuVisible ||
                    scaleMenuVisible ||
                    playbackError != null
                ),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
        Box(Modifier.fillMaxSize()) {
        PlayerChromeScrims()
        PlayerChromeButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
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
            if (playback.episodes.size > 1) {
                PlayerChromeButton(
                    icon = Icons.Outlined.PlaylistPlay,
                    contentDescription = "Список серий",
                    onClick = { episodePickerVisible = true },
                )
            }
            PlayerChromeButton(
                icon = Icons.Outlined.HighQuality,
                contentDescription = "Поток и озвучка",
                onClick = { qualityMenuVisible = true },
            )
            if (selectedStream.type != OnlineStreamType.EMBED) {
                PlayerChromeButton(
                    icon = Icons.Outlined.AspectRatio,
                    contentDescription = "Масштаб видео",
                    onClick = { scaleMenuVisible = true },
                    active = videoScaleMode != VideoScaleMode.FIT,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Subtitles,
                    contentDescription = "Аудио и субтитры",
                    onClick = { if (nativePlayer != null) tracksMenuVisible = true },
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Speed,
                    contentDescription = "Скорость",
                    onClick = { speedMenuVisible = true },
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
            } else {
                Surface(
                    onClick = {
                        val durationMs = episode.durationMs.coerceAtLeast(0L)
                        onSaveProgress(durationMs, durationMs, true)
                        isMarkedWatched = true
                    },
                    enabled = !isMarkedWatched,
                    color = if (isMarkedWatched) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                    } else {
                        Color.Black.copy(alpha = 0.62f)
                    },
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.12f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (isMarkedWatched) "Просмотрено" else "Отметить просмотрено",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        if (speedMenuVisible) {
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
                        subtitle = "Настройка запоминается отдельно для этого тайтла.",
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
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

        if (qualityMenuVisible) {
            ModalBottomSheet(
                onDismissRequest = { qualityMenuVisible = false },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                ) {
                    VaultSheetHeader(
                        title = "Поток и озвучка",
                        subtitle = "Озвучки сгруппированы по вариантам. Выбор запоминается для следующих серий.",
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    val groupedStreams = episode.streams.groupBy { stream ->
                        stream.translation?.takeIf(String::isNotBlank)
                            ?: stream.sourceName?.takeIf(String::isNotBlank)
                            ?: "Авто"
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp),
                    ) {
                        groupedStreams.forEach { (translation, streams) ->
                            item(key = "header:$translation") {
                                Text(
                                    text = translation,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 7.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            items(
                                items = streams.sortedWith(
                                    compareByDescending<OnlineStream> { it.quality ?: 0 }
                                        .thenBy { it.sourceName.orEmpty() }
                                        .thenBy { it.displayName },
                                ),
                                key = { "stream:${it.id}:${it.url}" },
                            ) { stream ->
                                val selected = stream == selectedStream
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 7.dp)
                                        .clickable {
                                            selectedStream = stream
                                            failedStreamKeys = emptySet()
                                            preferences.preferredTranslation = stream.translation
                                            preferences.preferredQuality = stream.quality
                                            preferences.preferredSourceName = stream.sourceName
                                            onSelectStream(stream)
                                            playbackError = null
                                            qualityMenuVisible = false
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.Black.copy(alpha = 0.30f),
                                        ) {
                                            Text(
                                                text = stream.quality?.let { "${it}p" } ?: "AUTO",
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = stream.sourceName?.takeIf(String::isNotBlank)
                                                    ?: if (stream.type == OnlineStreamType.EMBED) "Веб-плеер" else "Прямой поток",
                                                maxLines = 1,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = when (stream.type) {
                                                    OnlineStreamType.HLS -> "HLS · нативный плеер"
                                                    OnlineStreamType.MP4 -> "MP4 · нативный плеер"
                                                    OnlineStreamType.EMBED -> "встроенный веб-плеер"
                                                },
                                                maxLines = 1,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                Icons.Outlined.CheckCircle,
                                                contentDescription = "Выбрано",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (episodePickerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable { episodePickerVisible = false },
            )
            OnlineEpisodePickerPanel(
                episodes = playback.episodes,
                currentEpisodeId = episode.id,
                progress = playback.episodeProgress,
                onDismiss = { episodePickerVisible = false },
                onSelect = { targetEpisodeId ->
                    episodePickerVisible = false
                    if (targetEpisodeId != episode.id) onPlayEpisode(targetEpisodeId)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        playbackError?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(error, modifier = Modifier.padding(16.dp), color = Color.White)
            }
        }

        if (!episodePickerVisible) {
            nativePlayer?.let { activePlayer ->
                PlayerTransportControls(
                    player = activePlayer,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .widthIn(max = 980.dp)
                    .fillMaxWidth(),
            ) {
                PlayerNowPlayingBar(
                    title = playback.releaseName,
                    subtitle = buildString {
                        episode.ordinal?.let { append("Серия ${it.toDisplayNumber()} · ") }
                        append(selectedStream.displayName)
                        append(" · ${playback.providerName}")
                    },
                    modifier = Modifier.widthIn(max = if (isLandscape) 580.dp else 440.dp),
                )
                nativePlayer?.let { activePlayer ->
                    Spacer(Modifier.padding(top = 3.dp))
                    PlayerTimeline(
                        player = activePlayer,
                        skipSettings = skipSettings,
                        fallbackDurationMs = episode.durationMs,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        }
        }
    }

    if (tracksMenuVisible && !isInPictureInPictureMode) {
        nativePlayer?.let { activePlayer ->
            PlayerTracksSheet(
                player = activePlayer,
                onDismiss = { tracksMenuVisible = false },
            )
        }
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
}

@Composable
private fun OnlineEpisodePickerPanel(
    episodes: List<OnlineEpisode>,
    currentEpisodeId: String,
    progress: Map<String, OnlineWatchProgress>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = episodes.indexOfFirst { it.id == currentEpisodeId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    Surface(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .heightIn(max = 430.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f),
        ),
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 10.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Серии",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = "${episodes.size} доступно",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    top = 4.dp,
                    end = 10.dp,
                    bottom = 14.dp,
                ),
            ) {
                items(episodes, key = OnlineEpisode::id) { item ->
                    val itemProgress = progress[item.id] ?: OnlineWatchProgress()
                    val current = item.id == currentEpisodeId
                    Surface(
                        onClick = { onSelect(item.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        color = if (current) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
                        },
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(38.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (current) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = item.ordinal?.toDisplayNumber() ?: "•",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name?.takeIf(String::isNotBlank)
                                        ?: item.ordinal?.let { "Серия ${it.toDisplayNumber()}" }
                                        ?: "Серия",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (itemProgress.isCompleted || itemProgress.positionMs > 0L) {
                                    Spacer(Modifier.size(5.dp))
                                    WatchProgressBar(
                                        progress = itemProgress.fraction,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp),
                                    )
                                }
                            }
                            if (itemProgress.isCompleted) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = "Просмотрено",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun NativeOnlinePlayer(
    playback: OnlinePlaybackBundle,
    stream: OnlineStream,
    initialPositionMs: Long,
    speed: Float,
    equalizer: PlayerEqualizerController,
    skipSettings: PlayerSkipSettings,
    showSkipDialog: Boolean,
    onDismissSkipDialog: () -> Unit,
    onSkipSettingsChanged: (PlayerSkipSettings) -> Unit,
    onPositionSaved: (Long, Long, Boolean) -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit,
    videoScaleMode: VideoScaleMode,
    onSingleTap: () -> Unit,
    onPinchScale: (Int) -> Unit,
    onPlayerAvailable: (Player?) -> Unit,
    isInPictureInPictureMode: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val episode = playback.episode
    var endHandled by remember(episode.id, stream.id) { mutableStateOf(false) }
    val player = remember(episode.id, stream.id) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(KODIK_USER_AGENT)
            .setDefaultRequestProperties(stream.headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                setMediaItem(stream.toMediaItem(episode.id))
                if (initialPositionMs > 0L) seekTo(initialPositionMs)
                playWhenReady = true
                prepare()
            }
    }

    LaunchedEffect(player, speed) {
        player.setPlaybackSpeed(speed)
    }

    DisposableEffect(player, playback.nextEpisodeId) {
        onPlayerAvailable(player)
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !endHandled) {
                    endHandled = true
                    val positionMs = player.currentPosition.coerceAtLeast(0L)
                    val durationMs = player.safeOnlineDuration(episode.durationMs)
                    if (isCredibleOnlineCompletion(positionMs, durationMs)) {
                        onPositionSaved(positionMs, durationMs, true)
                        onEnded()
                    } else {
                        onPositionSaved(positionMs, durationMs, false)
                        onError(
                            "Поток завершился до начала серии. Прогресс не отмечен; " +
                                "выберите другой поток или вернитесь к списку серий.",
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    PLAYER_LOG_TAG,
                    "Media3: ${error.errorCodeName}; url=${stream.url}",
                    error,
                )
                onError("Не удалось воспроизвести поток. Выберите другой вариант или качество.")
            }
        }
        player.addListener(listener)
        equalizer.attach(player.audioSessionId)
        onDispose {
            onPlayerAvailable(null)
            player.removeListener(listener)
            if (!endHandled) {
                onPositionSaved(player.currentPosition, player.safeOnlineDuration(episode.durationMs), false)
            }
            equalizer.release()
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            delay(5_000)
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
                onPositionSaved(player.currentPosition, player.safeOnlineDuration(episode.durationMs), false)
            }
        }
    }

    PlayerStopEffect(player) {
        if (!endHandled) {
            onPositionSaved(player.currentPosition, player.safeOnlineDuration(episode.durationMs), false)
        }
    }

    LaunchedEffect(player, skipSettings) {
        while (isActive) {
            delay(ONLINE_AUTO_SKIP_POLL_MS)
            if (!player.isPlaying) continue
            autoSkipDecision(
                settings = skipSettings,
                positionMs = player.currentPosition,
                durationMs = player.safeOnlineDuration(episode.durationMs),
            )?.let { decision ->
                player.seekTo(decision.targetMs)
            }
        }
    }

    PlayerSurface(
        modifier = modifier,
        player = player,
        showController = false,
        videoScaleMode = videoScaleMode,
        onSingleTap = onSingleTap,
        onPinchScale = onPinchScale,
    )

    if (showSkipDialog && !isInPictureInPictureMode) {
        SkipSettingsDialog(
            settings = skipSettings,
            currentPositionMs = { player.currentPosition.coerceAtLeast(0L) },
            durationMs = { player.safeOnlineDuration(episode.durationMs) },
            onDismiss = onDismissSkipDialog,
            onSave = onSkipSettingsChanged,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedOnlinePlayer(
    stream: OnlineStream,
    onLoadingChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val webView = remember(stream.id) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString + " AnimeVault/${BuildConfig.VERSION_NAME}"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    onLoadingChanged(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    onLoadingChanged(false)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            loadUrl(stream.url, stream.headers)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = {},
    )
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

private fun OnlineStream.toMediaItem(episodeId: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(episodeId)
    .setMimeType(
        when (type) {
            OnlineStreamType.HLS -> MimeTypes.APPLICATION_M3U8
            OnlineStreamType.MP4 -> MimeTypes.VIDEO_MP4
            OnlineStreamType.EMBED -> null
        },
    )
    .build()

private fun Player.safeOnlineDuration(fallbackMs: Long): Long =
    duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: fallbackMs

internal fun selectFallbackStream(
    streams: List<OnlineStream>,
    current: OnlineStream,
    failedStreamKeys: Set<String>,
): OnlineStream? {
    val currentTranslation = current.translation?.trim()?.lowercase(Locale.ROOT)
    return streams
        .asSequence()
        .filter { it.failureKey() !in failedStreamKeys }
        .filter { it.url.isNotBlank() }
        .sortedWith(
            compareByDescending<OnlineStream> {
                !currentTranslation.isNullOrBlank() && it.translation?.trim()?.lowercase(Locale.ROOT) == currentTranslation
            }.thenByDescending { it.type != OnlineStreamType.EMBED }
                .thenByDescending { it.quality ?: 0 }
                .thenBy { it.displayName },
        )
        .firstOrNull()
}

internal fun OnlineStream.failureKey(): String = "$type\u001F$url"

internal fun isCredibleOnlineCompletion(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs < MINIMUM_EPISODE_DURATION_MS) return false
    val completionWindowMs = minOf(
        COMPLETION_TAIL_MS,
        (durationMs * (1.0 - COMPLETION_FRACTION)).toLong(),
    )
    return positionMs.coerceAtLeast(0L) >= durationMs - completionWindowMs
}

private const val MINIMUM_EPISODE_DURATION_MS = 60_000L
private const val COMPLETION_TAIL_MS = 30_000L
private const val COMPLETION_FRACTION = 0.92
private const val PLAYER_LOG_TAG = "AnimeVaultPlayer"
private const val ONLINE_AUTO_SKIP_POLL_MS = 300L

private fun Double.toDisplayNumber(): String = if (this % 1.0 == 0.0) {
    toLong().toString()
} else {
    toString().trimEnd('0').trimEnd('.')
}

@Composable
private fun OnlinePlayerMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
