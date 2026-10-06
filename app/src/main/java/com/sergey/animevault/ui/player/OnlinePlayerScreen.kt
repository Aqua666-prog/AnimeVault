package com.sergey.animevault.ui.player

import android.util.Log
import android.os.SystemClock
import android.widget.Toast
import com.sergey.animevault.R
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SkipNext
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sergey.animevault.data.download.OfflineMediaCache
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.playback.OnlineStreamResolver
import com.sergey.animevault.data.playback.OnlineStreamVariantKeys
import com.sergey.animevault.data.playback.PlaybackAdaptiveQualityPolicy
import com.sergey.animevault.data.playback.PlaybackAdaptiveQualityState
import com.sergey.animevault.data.playback.PlaybackCompletionPolicy
import com.sergey.animevault.data.playback.PlaybackFailure
import com.sergey.animevault.data.playback.PlaybackEnginePhase
import com.sergey.animevault.data.playback.PlaybackFailureClassifier
import com.sergey.animevault.data.playback.PlaybackFailureKind
import com.sergey.animevault.data.playback.PlaybackRecoveryAction
import com.sergey.animevault.data.playback.PlaybackPlayerRetryPolicy
import com.sergey.animevault.data.playback.PlaybackPrefetchPolicy
import com.sergey.animevault.data.playback.PlaybackRecoveryPolicy
import com.sergey.animevault.data.playback.PlaybackRouteHealthTracker
import com.sergey.animevault.data.playback.PlaybackSourceExhaustion
import com.sergey.animevault.data.playback.PlaybackStallDetector
import com.sergey.animevault.data.playback.PlaybackStreamCache
import com.sergey.animevault.data.playback.PlaybackVariant
import com.sergey.animevault.data.playback.toTransportSource
import com.sergey.animevault.data.playback.PlaybackVariantKind
import com.sergey.animevault.data.playback.toTransportSource
import com.sergey.animevault.data.playback.PlaybackVariantPreference
import com.sergey.animevault.data.playback.toTransportSource
import com.sergey.animevault.data.playback.PlaybackVariantResolver
import com.sergey.animevault.data.playback.toTransportSource
import com.sergey.animevault.data.transport.toMediaTransportSource
import com.sergey.animevault.data.playback.toTransportFailureKind
import com.sergey.animevault.data.transport.TransportFailurePolicy
import com.sergey.animevault.data.transport.TransportRefreshResolver
import com.sergey.animevault.data.playback.toPlaybackVariant
import com.sergey.animevault.data.playback.PlaybackSession
import com.sergey.animevault.data.playback.PlaybackSessionEvent
import com.sergey.animevault.data.playback.PlaybackSessionStore
import com.sergey.animevault.data.kodik.KODIK_USER_AGENT
import com.sergey.animevault.data.kodik.KodikStreamResolver
import com.sergey.animevault.data.kodik.normalizeHttpsUrl
import com.sergey.animevault.ui.online.OnlinePlaybackBundle
import com.sergey.animevault.ui.online.OnlinePlayerUiState
import com.sergey.animevault.ui.online.OnlinePlayerViewModel
import com.sergey.animevault.ui.components.WatchProgressBar
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.theme.vaultMotionDuration
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun OnlinePlayerRoute(
    viewModel: OnlinePlayerViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackSession by viewModel.playbackSession.collectAsStateWithLifecycle()
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
            playbackSession = playbackSession,
            onPlaybackSessionEvent = viewModel::onPlaybackSessionEvent,
            onSaveProgress = viewModel::saveProgress,
            onSelectStream = viewModel::selectStream,
            onRefreshStreams = viewModel::refreshStreams,
            onPrefetchNextEpisode = viewModel::prefetchNextEpisode,
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
    val directSessionStore = remember { PlaybackSessionStore() }
    val directPlaybackSession by directSessionStore.state.collectAsStateWithLifecycle()
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
            playbackSession = directPlaybackSession,
            onPlaybackSessionEvent = { event -> directSessionStore.dispatch(event) },
            onSaveProgress = { _, _, _ -> },
            onSelectStream = {},
            onRefreshStreams = { null },
            onPrefetchNextEpisode = { false },
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
internal fun OnlineVideoPlayer(
    playback: OnlinePlaybackBundle,
    playbackSession: PlaybackSession,
    onPlaybackSessionEvent: (PlaybackSessionEvent) -> Unit,
    onSaveProgress: (Long, Long, Boolean) -> Unit,
    onSelectStream: (OnlineStream) -> Unit,
    onRefreshStreams: suspend () -> List<OnlineStream>?,
    onPrefetchNextEpisode: suspend () -> Boolean,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    isInPictureInPictureMode: Boolean,
    onEnterPictureInPicture: () -> Boolean,
) {
    val episode = playback.episode
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playbackPlan = playback.playbackPlan
    val sessionStartPosition = if (playbackSession.episodeKey == playbackPlan.episodeKey) {
        playbackSession.positionMs
    } else {
        playbackPlan.progress.positionMs
    }
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
    var selectedVariant by remember(episode.id) {
        mutableStateOf(
            playbackPlan.variants.firstOrNull { it.key == playbackSession.variantKey }
                ?: PlaybackVariantResolver.selectPreferred(
                    variants = playbackPlan.variants,
                    preference = PlaybackVariantPreference(
                        translation = preferences.preferredTranslation,
                        quality = preferences.preferredQuality,
                        sourceName = preferences.preferredSourceName,
                        providerId = playback.providerId,
                        preferLocal = true,
                    ),
                ),
        )
    }
    val selectedStream = episode.streams.firstOrNull { stream ->
        OnlineStreamVariantKeys.keyOf(stream) == selectedVariant.key
    }
    var overlayState by remember(episode.id) { mutableStateOf(PlayerOverlayState()) }
    var controlsLocked by remember(episode.id) { mutableStateOf(false) }
    val chromeFade = vaultMotionDuration(VaultMotion.fast)
    BackHandler(enabled = controlsLocked) { controlsLocked = false }
    val dispatchOverlay: (PlayerOverlayEvent) -> Unit = { event ->
        overlayState = PlayerOverlayReducer.reduce(overlayState, event)
    }
    var nextEpisodeMode by remember(preferenceTitleKey) { mutableStateOf(preferences.nextEpisodeMode) }
    var pendingNextEpisodeId by remember(episode.id) { mutableStateOf<String?>(null) }
    var nextEpisodeCountdown by remember(episode.id) { mutableStateOf<Int?>(null) }
    val providerSkipData = (selectedStream?.skipData ?: episode.skipData)?.validated(episode.durationMs)
    val skipScopeKey = buildString {
        append(playback.providerId).append('|').append(playback.releaseId).append('|').append(episode.id)
        append('|').append(selectedStream?.translationPreferenceKey.orEmpty())
        append('|').append(selectedStream?.hostFamily.orEmpty())
    }
    fun resolvedSkipSettings(): PlayerSkipSettings {
        if (preferences.providerSkipDisabled(skipScopeKey)) return PlayerSkipSettings()
        preferences.scopedSkipSettings(skipScopeKey)?.let { return it }
        if (preferences.hasManualTitleSkipSettings()) return preferences.skipSettings
        return providerSkipData?.toPlayerSkipSettings() ?: preferences.skipSettings
    }
    var skipSettings by remember(preferenceTitleKey, episode.id, selectedVariant.key) {
        mutableStateOf(resolvedSkipSettings())
    }
    var speed by remember(preferenceTitleKey) {
        mutableFloatStateOf(preferences.speed)
    }
    var resumePosition by remember(episode.id) {
        mutableLongStateOf(sessionStartPosition)
    }
    var resumePlayWhenReady by remember(episode.id) {
        mutableStateOf(
            if (playbackSession.episodeKey == playbackPlan.episodeKey) {
                playbackSession.playWhenReady
            } else {
                true
            },
        )
    }
    var playbackError by remember(episode.id) { mutableStateOf<String?>(null) }
    var failedStreamKeys by remember(episode.id) { mutableStateOf(emptySet<String>()) }
    var refreshedStreamIdentities by remember(episode.id) { mutableStateOf(emptySet<String>()) }
    var retryCountByVariant by remember(episode.id) { mutableStateOf(emptyMap<String, Int>()) }
    var softRetryGeneration by remember(episode.id) { mutableStateOf(0) }
    var playerRebuildGeneration by remember(episode.id) { mutableStateOf(0) }
    var recoveryInFlight by remember(episode.id) { mutableStateOf(false) }
    var adaptiveQualityState by remember(episode.id) { mutableStateOf(PlaybackAdaptiveQualityState()) }
    var nextEpisodePrefetched by remember(episode.id) { mutableStateOf(false) }
    var nextEpisodePrefetchInFlight by remember(episode.id) { mutableStateOf(false) }
    val routeHealthTracker = remember(episode.id) { PlaybackRouteHealthTracker() }
    var webLoading by remember(selectedVariant.key) { mutableStateOf(false) }
    var isMarkedWatched by remember(episode.id) { mutableStateOf(playback.progress.isCompleted) }
    var videoScaleMode by remember(preferenceTitleKey) { mutableStateOf(preferences.videoScaleMode) }
    var anime4kEnabled by remember(preferenceTitleKey) { mutableStateOf(preferences.anime4kEnabled) }
    var nativePlayer by remember(episode.id) { mutableStateOf<Player?>(null) }
    var sleepTimer by remember { mutableStateOf(SleepTimerState()) }

    fun switchVariant(next: PlaybackVariant, rememberPreference: Boolean) {
        if (next.key == selectedVariant.key) return
        nativePlayer?.let { activePlayer ->
            val positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            val durationMs = activePlayer.safeOnlineDuration(episode.durationMs)
            resumePosition = positionMs
            resumePlayWhenReady = activePlayer.playWhenReady
            onSaveProgress(positionMs, durationMs, false)
            onPlaybackSessionEvent(
                PlaybackSessionEvent.Timeline(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = activePlayer.bufferedPosition.coerceAtLeast(0L),
                ),
            )
        }
        onPlaybackSessionEvent(PlaybackSessionEvent.SwitchVariant(next.key))
        selectedVariant = next
        playbackError = null
        val onlineStream = episode.streams.firstOrNull { stream ->
            OnlineStreamVariantKeys.keyOf(stream) == next.key
        }
        if (rememberPreference && onlineStream != null) {
            // A manual choice is an explicit override: clear episode failures and give the
            // selected CDN a fresh chance even if it was cooling down automatically.
            failedStreamKeys = emptySet()
            retryCountByVariant = emptyMap()
            refreshedStreamIdentities = emptySet()
            adaptiveQualityState = PlaybackAdaptiveQualityState()
            routeHealthTracker.forgive(next)
            preferences.preferredTranslation = onlineStream.translation
            preferences.preferredQuality = onlineStream.quality
            preferences.preferredSourceName = onlineStream.sourceName
            onSelectStream(onlineStream)
        }
    }

    fun rememberCurrentPlaybackPosition() {
        nativePlayer?.let { activePlayer ->
            val positionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            val durationMs = activePlayer.safeOnlineDuration(episode.durationMs)
            resumePosition = positionMs
            resumePlayWhenReady = activePlayer.playWhenReady
            onSaveProgress(positionMs, durationMs, false)
            onPlaybackSessionEvent(
                PlaybackSessionEvent.Timeline(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = activePlayer.bufferedPosition.coerceAtLeast(0L),
                ),
            )
        }
    }

    fun executeRecoveryAction(
        action: PlaybackRecoveryAction,
        failure: PlaybackFailure,
        currentVariant: PlaybackVariant,
    ) {
        val currentKey = currentVariant.key
        when (action) {
            is PlaybackRecoveryAction.RetryCurrent -> {
                rememberCurrentPlaybackPosition()
                retryCountByVariant = retryCountByVariant +
                    (currentKey to ((retryCountByVariant[currentKey] ?: 0) + 1))
                playbackError = null
                recoveryInFlight = true
                Toast.makeText(
                    context,
                    "Соединение прервалось. Переподключаю ${currentVariant.displayName}",
                    Toast.LENGTH_SHORT,
                ).show()
                coroutineScope.launch {
                    delay(action.delayMs)
                    if (selectedVariant.key == currentKey) {
                        if (PlaybackPlayerRetryPolicy.reusePlayerInstance(currentVariant.kind)) {
                            // HLS can retry in the same ExoPlayer instance; keep decoder/cache/session warm.
                            softRetryGeneration += 1
                        } else {
                            playerRebuildGeneration += 1
                        }
                    }
                    recoveryInFlight = false
                }
            }

            PlaybackRecoveryAction.RefreshCurrent -> {
                recoveryInFlight = false
            }

            is PlaybackRecoveryAction.SwitchVariant -> {
                failedStreamKeys = failedStreamKeys + currentKey
                recoveryInFlight = false
                Toast.makeText(
                    context,
                    "${currentVariant.displayName} не отвечает. Пробую ${action.variant.displayName}",
                    Toast.LENGTH_SHORT,
                ).show()
                switchVariant(action.variant, rememberPreference = false)
            }

            is PlaybackRecoveryAction.GiveUp -> {
                val failed = failedStreamKeys + currentKey
                failedStreamKeys = failed
                val exhaustion = PlaybackSourceExhaustion.inspect(
                    variants = playbackPlan.variants,
                    failedVariantKeys = failed,
                    blockedHostFamilies = routeHealthTracker.blockedHostFamilies(),
                )
                playbackError = exhaustion.userMessage(
                    action.failure.userMessage(playback.providerName),
                )
                recoveryInFlight = false
            }
        }
    }

    fun recoverWithoutRefresh(
        failure: PlaybackFailure,
        currentVariant: PlaybackVariant = selectedVariant,
        routeFailureAlreadyRecorded: Boolean = false,
    ) {
        if (recoveryInFlight) return
        if (!routeFailureAlreadyRecorded) {
            routeHealthTracker.recordFailure(currentVariant, failure)
        }
        val action = PlaybackRecoveryPolicy.decide(
            variants = playbackPlan.variants,
            current = currentVariant,
            failedVariantKeys = failedStreamKeys,
            failure = failure,
            retryCountForVariant = retryCountByVariant[currentVariant.key] ?: 0,
            canRefreshCurrent = false,
            refreshAlreadyAttempted = true,
            blockedHostFamilies = routeHealthTracker.blockedHostFamilies(),
        )
        executeRecoveryAction(action, failure, currentVariant)
    }

    fun handlePlaybackFailure(failure: PlaybackFailure) {
        if (recoveryInFlight) return
        val currentVariant = selectedVariant
        val currentStream = episode.streams.firstOrNull { stream ->
            OnlineStreamVariantKeys.keyOf(stream) == currentVariant.key
        }
        routeHealthTracker.recordFailure(currentVariant, failure)

        val refreshIdentity = currentStream?.streamRefreshIdentity(
            ownerProviderId = currentVariant.providerId ?: playback.providerId,
        )
        val refreshAttempted = refreshIdentity != null &&
            refreshIdentity in refreshedStreamIdentities
        val canRefresh = refreshIdentity != null &&
            shouldRefreshExpiredOnlineStream(
                failure = failure,
                stream = currentStream,
            )

        val adaptiveNowMs = SystemClock.elapsedRealtime()
        val updatedAdaptiveState = PlaybackAdaptiveQualityPolicy.registerDegradation(
            state = adaptiveQualityState,
            failure = failure,
            nowMs = adaptiveNowMs,
        )
        adaptiveQualityState = updatedAdaptiveState
        val blockedHostFamilies = routeHealthTracker.blockedHostFamilies()
        if (
            (!canRefresh || refreshAttempted) &&
            PlaybackAdaptiveQualityPolicy.shouldDownshift(updatedAdaptiveState, adaptiveNowMs) &&
            !PlaybackAdaptiveQualityPolicy.hasHealthySameQualityAlternative(
                variants = playbackPlan.variants,
                current = currentVariant,
                failedVariantKeys = failedStreamKeys,
                blockedHostFamilies = blockedHostFamilies,
            )
        ) {
            val downshift = PlaybackAdaptiveQualityPolicy.selectDownshift(
                variants = playbackPlan.variants,
                current = currentVariant,
                failedVariantKeys = failedStreamKeys,
                blockedHostFamilies = blockedHostFamilies,
            )
            if (downshift != null) {
                adaptiveQualityState = PlaybackAdaptiveQualityPolicy.markAutomaticDownshift(
                    state = updatedAdaptiveState,
                    fromQuality = currentVariant.quality,
                    nowMs = adaptiveNowMs,
                )
                recoveryInFlight = false
                Toast.makeText(
                    context,
                    "Сеть нестабильна. Временно снижаю качество до ${downshift.quality ?: "авто"}${if (downshift.quality != null) "p" else ""}",
                    Toast.LENGTH_SHORT,
                ).show()
                switchVariant(downshift, rememberPreference = false)
                return
            }
        }

        val action = PlaybackRecoveryPolicy.decide(
            variants = playbackPlan.variants,
            current = currentVariant,
            failedVariantKeys = failedStreamKeys,
            failure = failure,
            retryCountForVariant = retryCountByVariant[currentVariant.key] ?: 0,
            canRefreshCurrent = canRefresh,
            refreshAlreadyAttempted = refreshAttempted,
            blockedHostFamilies = blockedHostFamilies,
        )

        if (action != PlaybackRecoveryAction.RefreshCurrent) {
            executeRecoveryAction(action, failure, currentVariant)
            return
        }
        if (refreshIdentity == null) {
            recoverWithoutRefresh(
                failure = failure,
                currentVariant = currentVariant,
                routeFailureAlreadyRecorded = true,
            )
            return
        }

        refreshedStreamIdentities = refreshedStreamIdentities + refreshIdentity
        rememberCurrentPlaybackPosition()
        recoveryInFlight = true
        coroutineScope.launch {
            val refreshedStreams = onRefreshStreams()
            if (!refreshedStreams.isNullOrEmpty()) {
                val ownerProviderId = currentVariant.providerId ?: playback.providerId
                val refreshedByKey = refreshedStreams.associateBy { stream ->
                    stream.toMediaTransportSource(ownerProviderId, playback.providerName).key
                }
                val refreshedSource = TransportRefreshResolver.selectReplacement(
                    current = currentVariant.toTransportSource(),
                    fresh = refreshedStreams.map { stream ->
                        stream.toMediaTransportSource(ownerProviderId, playback.providerName)
                    },
                )
                val refreshedStream = refreshedSource?.let { refreshedByKey[it.key] }

                if (refreshedStream != null) {
                    val replacement = refreshedStream.toPlaybackVariant(
                        episodeKey = playbackPlan.episodeKey,
                        providerId = playback.providerId,
                        providerName = playback.providerName,
                    )
                    val sameConcreteUrl = replacement.key == selectedVariant.key
                    selectedVariant = replacement
                    // A changed URL already changes the remember key. If only headers/expiry changed,
                    // explicitly rebuild the data source so Media3 uses the fresh request metadata.
                    if (sameConcreteUrl) playerRebuildGeneration += 1
                }
                playbackError = null
                recoveryInFlight = false
                Toast.makeText(
                    context,
                    "Обновляю временную ссылку потока",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                recoveryInFlight = false
                recoverWithoutRefresh(
                    failure = failure,
                    currentVariant = currentVariant,
                    routeFailureAlreadyRecorded = true,
                )
            }
        }
    }
    LaunchedEffect(playbackPlan.variants) {
        val sameKey = playbackPlan.variants.firstOrNull { it.key == selectedVariant.key }
        if (sameKey != null) {
            // A provider may refresh headers/expiry while keeping the same URL. Keep the
            // selected variant object in sync and recreate Media3 so new request metadata is used.
            if (sameKey != selectedVariant) {
                selectedVariant = sameKey
                playerRebuildGeneration += 1
                playbackError = null
            }
        } else {
            val refreshedVariant = PlaybackVariantResolver.selectPreferred(
                variants = playbackPlan.variants,
                preference = PlaybackVariantPreference(
                    translation = selectedVariant.translation,
                    quality = selectedVariant.quality,
                    sourceName = selectedVariant.sourceName,
                    providerId = selectedVariant.providerId ?: playback.providerId,
                    preferLocal = false,
                ),
            )
            failedStreamKeys = emptySet()
            switchVariant(refreshedVariant, rememberPreference = false)
        }
    }
    LaunchedEffect(skipScopeKey, providerSkipData) {
        skipSettings = resolvedSkipSettings()
    }
    LaunchedEffect(playbackError) {
        playbackError?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(selectedVariant.key, playbackSession.phase) {
        if (playbackSession.phase == PlaybackEnginePhase.PLAYING && !selectedVariant.isLocal) {
            // Five uninterrupted seconds of PLAYING is strong enough evidence to clear a
            // temporary CDN cooldown accumulated during recovery.
            delay(5_000L)
            routeHealthTracker.recordSuccess(selectedVariant)
        }
    }
    LaunchedEffect(
        selectedVariant.key,
        playbackSession.phase,
        adaptiveQualityState.recoveryTargetQuality,
    ) {
        if (
            playbackSession.phase != PlaybackEnginePhase.PLAYING ||
            adaptiveQualityState.recoveryTargetQuality == null ||
            selectedVariant.isLocal
        ) {
            return@LaunchedEffect
        }
        delay(PlaybackAdaptiveQualityPolicy.STABLE_UPSHIFT_DELAY_MS)
        if (playbackSession.phase != PlaybackEnginePhase.PLAYING) return@LaunchedEffect
        val upshift = PlaybackAdaptiveQualityPolicy.selectRecoveryUpshift(
            variants = playbackPlan.variants,
            current = selectedVariant,
            state = adaptiveQualityState,
            failedVariantKeys = failedStreamKeys,
            blockedHostFamilies = routeHealthTracker.blockedHostFamilies(),
        ) ?: return@LaunchedEffect
        adaptiveQualityState = PlaybackAdaptiveQualityPolicy.markRecoveryUpshift(
            state = adaptiveQualityState,
            selectedQuality = upshift.quality,
            nowMs = SystemClock.elapsedRealtime(),
        )
        Toast.makeText(
            context,
            "Сеть стабилизировалась. Возвращаю ${upshift.quality?.let { "${it}p" } ?: "авто"}",
            Toast.LENGTH_SHORT,
        ).show()
        switchVariant(upshift, rememberPreference = false)
    }

    LaunchedEffect(nativePlayer, playback.nextEpisodeId, nextEpisodePrefetched) {
        val activePlayer = nativePlayer ?: return@LaunchedEffect
        if (playback.nextEpisodeId == null || nextEpisodePrefetched) return@LaunchedEffect
        while (isActive && !nextEpisodePrefetched) {
            delay(2_000L)
            val durationMs = activePlayer.safeOnlineDuration(episode.durationMs)
            if (
                PlaybackPrefetchPolicy.shouldPrefetchNextEpisode(
                    positionMs = activePlayer.currentPosition.coerceAtLeast(0L),
                    durationMs = durationMs,
                    hasNextEpisode = playback.nextEpisodeId != null,
                    alreadyPrefetched = nextEpisodePrefetched || nextEpisodePrefetchInFlight,
                )
            ) {
                nextEpisodePrefetchInFlight = true
                val prefetched = runCatchingCancellable { onPrefetchNextEpisode() }.getOrDefault(false)
                nextEpisodePrefetchInFlight = false
                if (prefetched) {
                    nextEpisodePrefetched = true
                    Log.d(PLAYER_LOG_TAG, "Next episode streams prefetched id=${playback.nextEpisodeId}")
                    return@LaunchedEffect
                }
                // A failed preflight is non-fatal; allow one later attempt while playback continues.
                delay(8_000L)
            }
        }
    }

    LaunchedEffect(selectedVariant.key, selectedVariant.kind) {
        if (selectedVariant.kind == PlaybackVariantKind.EMBED) {
            if (overlayState.isOpen(PlayerOverlay.SKIP_SETTINGS)) {
                dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.SKIP_SETTINGS))
            }
            onPlaybackSessionEvent(
                PlaybackSessionEvent.Prepare(
                    episodeKey = playbackPlan.episodeKey,
                    variantKey = selectedVariant.key,
                    positionMs = resumePosition,
                    durationMs = playbackPlan.progress.durationMs.takeIf { it > 0L } ?: episode.durationMs,
                    speed = speed,
                    playWhenReady = true,
                ),
            )
        }
    }

    LaunchedEffect(webLoading, selectedVariant.key, selectedVariant.kind) {
        if (selectedVariant.kind == PlaybackVariantKind.EMBED) {
            onPlaybackSessionEvent(
                if (webLoading) PlaybackSessionEvent.Buffering else PlaybackSessionEvent.Ready,
            )
        }
    }

    LaunchedEffect(nativePlayer, overlayState.active) {
        if (overlayState.isOpen(PlayerOverlay.TRACKS) && nativePlayer == null) {
            dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.TRACKS))
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
            onPlayEpisode(nextId)
        }
    }

    LaunchedEffect(sleepTimer, nativePlayer) {
        val deadline = sleepTimer.deadlineMs ?: return@LaunchedEffect
        while (sleepTimer.deadlineMs == deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                nativePlayer?.pause()
                sleepTimer = SleepTimerState()
                dispatchOverlay(PlayerOverlayEvent.ShowChrome)
                break
            }
            delay(remaining.coerceAtMost(1_000L))
        }
    }

    LaunchedEffect(
        overlayState,
        pendingNextEpisodeId,
        playbackSession.phase,
        selectedVariant.key,
        selectedVariant.kind,
    ) {
        val playbackCanAutoHide = selectedVariant.kind == PlaybackVariantKind.EMBED ||
            playbackSession.phase == PlaybackEnginePhase.PLAYING
        if (overlayState.canAutoHide(
                playbackActive = playbackCanAutoHide,
                transientOverlayVisible = pendingNextEpisodeId != null,
            )
        ) {
            delay(4_500L)
            if (selectedVariant.kind == PlaybackVariantKind.EMBED ||
                playbackSession.phase == PlaybackEnginePhase.PLAYING
            ) {
                dispatchOverlay(PlayerOverlayEvent.HideChrome)
            }
        }
    }

    LaunchedEffect(isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            dispatchOverlay(PlayerOverlayEvent.Dismiss())
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val isLandscape = maxWidth > maxHeight
        if (selectedVariant.kind == PlaybackVariantKind.EMBED) {
            val embedStream = requireNotNull(selectedStream) { "Для веб-варианта не найден исходный поток" }
            EmbeddedOnlinePlayer(
                stream = embedStream,
                onLoadingChanged = { webLoading = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (webLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            NativeOnlinePlayer(
                playback = playback,
                playbackSession = playbackSession,
                onPlaybackSessionEvent = onPlaybackSessionEvent,
                variant = selectedVariant,
                softRetryGeneration = softRetryGeneration,
                playerRebuildGeneration = playerRebuildGeneration,
                initialPositionMs = resumePosition,
                initialPlayWhenReady = resumePlayWhenReady,
                speed = speed,
                equalizer = equalizer,
                defaultSubtitlesEnabled = preferences.defaultSubtitlesEnabled,
                anime4kEnabled = anime4kEnabled,
                onAnime4kFailure = {
                    anime4kEnabled = false
                    preferences.anime4kEnabled = false
                    Toast.makeText(context, "Anime4K отключён после ошибки GPU", Toast.LENGTH_LONG).show()
                },
                onPositionSaved = { position, duration, ended ->
                    resumePosition = if (ended) 0L else position
                    onSaveProgress(position, duration, ended)
                },
                onEnded = {
                    if (shouldSleepTimerPause(sleepTimer, SystemClock.elapsedRealtime(), episodeEnded = true)) {
                        sleepTimer = SleepTimerState()
                        nativePlayer?.pause()
                        dispatchOverlay(PlayerOverlayEvent.ShowChrome)
                    } else {
                        when (val decision = nextEpisodeDecision(nextEpisodeMode, playback.nextEpisodeId)) {
                            NextEpisodeDecision.Stop -> Unit
                            is NextEpisodeDecision.PlayNow -> onPlayEpisode(decision.id)
                            is NextEpisodeDecision.Countdown -> pendingNextEpisodeId = decision.id
                        }
                    }
                },
                skipSettings = skipSettings,
                showSkipDialog = overlayState.isOpen(PlayerOverlay.SKIP_SETTINGS),
                onDismissSkipDialog = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.SKIP_SETTINGS)) },
                onSkipSettingsChanged = { updated ->
                    preferences.setProviderSkipDisabled(skipScopeKey, false)
                    preferences.setScopedSkipSettings(skipScopeKey, updated)
                    skipSettings = updated
                },
                onUseProviderSkipData = providerSkipData?.let { data ->
                    {
                        preferences.clearScopedSkipSettings(skipScopeKey)
                        preferences.setProviderSkipDisabled(skipScopeKey, false)
                        skipSettings = data.toPlayerSkipSettings()
                    }
                },
                onDisableProviderSkipData = providerSkipData?.let {
                    {
                        preferences.clearScopedSkipSettings(skipScopeKey)
                        preferences.setProviderSkipDisabled(skipScopeKey, true)
                        skipSettings = PlayerSkipSettings()
                    }
                },
                onError = { failure ->
                    handlePlaybackFailure(failure)
                },
                videoScaleMode = videoScaleMode,
                onSingleTap = { dispatchOverlay(PlayerOverlayEvent.ToggleChrome) },
                onPinchScale = { direction ->
                    videoScaleMode = videoScaleMode.step(direction)
                    preferences.videoScaleMode = videoScaleMode
                    dispatchOverlay(PlayerOverlayEvent.ShowChrome)
                },
                onPlayerAvailable = { nativePlayer = it },
                isInPictureInPictureMode = isInPictureInPictureMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (controlsLocked && !isInPictureInPictureMode) {
            PlayerTouchLock(onUnlock = { controlsLocked = false; dispatchOverlay(PlayerOverlayEvent.ShowChrome) })
        }

        AnimatedVisibility(
            visible = !isInPictureInPictureMode && !controlsLocked && (
                selectedVariant.kind == PlaybackVariantKind.EMBED ||
                    overlayState.shouldRenderChrome(
                        transientOverlayVisible = pendingNextEpisodeId != null || playbackError != null,
                    )
                ),
            enter = fadeIn(tween(chromeFade)),
            exit = fadeOut(tween(chromeFade)),
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
            PlayerChromeButton(
                icon = Icons.Outlined.Lock,
                contentDescription = "Заблокировать плеер",
                onClick = { controlsLocked = true },
            )
            PlayerChromeButton(
                icon = Icons.Outlined.ScreenRotation,
                contentDescription = stringResource(R.string.player_rotate_screen),
                onClick = { togglePlayerOrientation(context) },
            )
            if (playback.episodes.size > 1) {
                PlayerChromeButton(
                    icon = Icons.Outlined.PlaylistPlay,
                    contentDescription = "Список серий",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.EPISODE_PICKER)) },
                )
            }
            if (playback.nextEpisodeId != null) {
                PlayerChromeButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "Следующая серия",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.NEXT_EPISODE)) },
                    active = nextEpisodeMode != NextEpisodeMode.OFF,
                )
            }
            PlayerChromeButton(
                icon = Icons.Outlined.HighQuality,
                contentDescription = "Поток и озвучка",
                onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.QUALITY)) },
            )
            if (selectedVariant.kind != PlaybackVariantKind.EMBED) {
                PlayerChromeButton(
                    icon = Icons.Outlined.AutoFixHigh,
                    contentDescription = "AnimeVault enhancer",
                    onClick = {
                        anime4kEnabled = !anime4kEnabled
                        preferences.anime4kEnabled = anime4kEnabled
                        if (anime4kEnabled) Toast.makeText(context, "✨ Улучшение изображения: Авто", Toast.LENGTH_SHORT).show()
                    },
                    active = anime4kEnabled,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.AspectRatio,
                    contentDescription = "Масштаб видео",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.VIDEO_SCALE)) },
                    active = videoScaleMode != VideoScaleMode.FIT,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Subtitles,
                    contentDescription = "Аудио и субтитры",
                    onClick = { if (nativePlayer != null) dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.TRACKS)) },
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Speed,
                    contentDescription = "Скорость",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.SPEED)) },
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Timer,
                    contentDescription = "Автопропуск",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.SKIP_SETTINGS)) },
                    active = skipSettings.autoSkipOpening || skipSettings.autoSkipEnding,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.Bedtime,
                    contentDescription = "Таймер сна",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.SLEEP_TIMER)) },
                    active = sleepTimer.active,
                )
                PlayerChromeButton(
                    icon = Icons.Outlined.GraphicEq,
                    contentDescription = "Эквалайзер",
                    onClick = { dispatchOverlay(PlayerOverlayEvent.Open(PlayerOverlay.EQUALIZER)) },
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
                        when (val decision = nextEpisodeDecision(nextEpisodeMode, playback.nextEpisodeId)) {
                            NextEpisodeDecision.Stop -> Unit
                            is NextEpisodeDecision.PlayNow -> onPlayEpisode(decision.id)
                            is NextEpisodeDecision.Countdown -> pendingNextEpisodeId = decision.id
                        }
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

        if (overlayState.isOpen(PlayerOverlay.SPEED)) {
            ModalBottomSheet(
                onDismissRequest = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.SPEED)) },
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
                                        dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.SPEED))
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

        if (overlayState.isOpen(PlayerOverlay.QUALITY)) {
            ModalBottomSheet(
                onDismissRequest = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.QUALITY)) },
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
                        subtitle = "Локальный файл имеет приоритет. Онлайн-озвучка и качество запоминаются для следующих серий.",
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
                        val localVariants = playbackPlan.variants.filter(PlaybackVariant::isLocal)
                        if (localVariants.isNotEmpty()) {
                            item(key = "header:local") {
                                Text(
                                    text = "На устройстве",
                                    modifier = Modifier.padding(top = 8.dp, bottom = 7.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            items(localVariants, key = PlaybackVariant::key) { variant ->
                                val selected = variant.key == selectedVariant.key
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 7.dp)
                                        .clickable {
                                            failedStreamKeys = emptySet()
                                            switchVariant(variant, rememberPreference = false)
                                            dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.QUALITY))
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
                                                text = "LOCAL",
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = "Локальный файл",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = "Без сети · ${variant.uri.substringAfterLast('/').take(48)}",
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
                                val selected = OnlineStreamVariantKeys.keyOf(stream) == selectedVariant.key
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 7.dp)
                                        .clickable {
                                            failedStreamKeys = emptySet()
                                            playbackPlan.variants.firstOrNull { it.key == stream.failureKey() }?.let { variant ->
                                                switchVariant(variant, rememberPreference = true)
                                            }
                                            dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.QUALITY))
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

        if (overlayState.isOpen(PlayerOverlay.EPISODE_PICKER)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.EPISODE_PICKER)) },
            )
            OnlineEpisodePickerPanel(
                episodes = playback.episodes,
                currentEpisodeId = episode.id,
                progress = playback.episodeProgress,
                onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.EPISODE_PICKER)) },
                onSelect = { targetEpisodeId ->
                    dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.EPISODE_PICKER))
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

        if (!overlayState.isOpen(PlayerOverlay.EPISODE_PICKER)) {
            if (
                selectedVariant.kind != PlaybackVariantKind.EMBED &&
                playbackSession.phase == PlaybackEnginePhase.PAUSED &&
                !overlayState.hasModalOverlay
            ) {
                PlayerPauseInfoOverlay(
                    title = playback.releaseName,
                    episodeLabel = episode.ordinal?.let { "Серия ${it.toDisplayNumber()} · ${selectedVariant.displayName}" }
                        ?: selectedVariant.displayName,
                    remainingMs = (playbackSession.durationMs - playbackSession.positionMs).coerceAtLeast(0L),
                    nextLabel = playback.nextEpisodeId?.let { "Следующая серия доступна" },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 18.dp),
                )
            }

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
                        append(selectedVariant.displayName)
                        if (!selectedVariant.isLocal) append(" · ${playback.providerName}")
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

        nextEpisodeCountdown?.let { countdown ->
            NextEpisodeCountdownOverlay(
                seconds = countdown,
                onCancel = {
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                },
                onPlayNow = {
                    val nextId = pendingNextEpisodeId
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                    nextId?.let(onPlayEpisode)
                },
                modifier = Modifier
                    .align(if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = if (isLandscape) 86.dp else 104.dp)
                    .widthIn(max = 390.dp),
            )
        }
        }
        }
    }

    if (overlayState.isOpen(PlayerOverlay.NEXT_EPISODE) && !isInPictureInPictureMode) {
        NextEpisodeModeSheet(
            mode = nextEpisodeMode,
            onModeSelected = { mode ->
                nextEpisodeMode = mode
                preferences.nextEpisodeMode = mode
                if (mode == NextEpisodeMode.OFF) {
                    pendingNextEpisodeId = null
                    nextEpisodeCountdown = null
                }
                dispatchOverlay(PlayerOverlayEvent.ShowChrome)
            },
            onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.NEXT_EPISODE)) },
        )
    }

    if (overlayState.isOpen(PlayerOverlay.TRACKS) && !isInPictureInPictureMode) {
        nativePlayer?.let { activePlayer ->
            PlayerTracksSheet(
                player = activePlayer,
                onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.TRACKS)) },
            )
        }
    }
    if (overlayState.isOpen(PlayerOverlay.VIDEO_SCALE) && !isInPictureInPictureMode) {
        VideoScaleModeSheet(
            selected = videoScaleMode,
            onSelected = { mode ->
                videoScaleMode = mode
                preferences.videoScaleMode = mode
                dispatchOverlay(PlayerOverlayEvent.ShowChrome)
            },
            onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.VIDEO_SCALE)) },
        )
    }

    if (overlayState.isOpen(PlayerOverlay.SLEEP_TIMER) && !isInPictureInPictureMode) {
        SleepTimerSheet(
            state = sleepTimer,
            nowMs = SystemClock::elapsedRealtime,
            onSelected = { sleepTimer = it },
            onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.SLEEP_TIMER)) },
        )
    }

    if (overlayState.isOpen(PlayerOverlay.EQUALIZER) && !isInPictureInPictureMode) {
        EqualizerSheet(
            controller = equalizer,
            onDismiss = { dispatchOverlay(PlayerOverlayEvent.Dismiss(PlayerOverlay.EQUALIZER)) },
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

@Composable
private fun NativeOnlinePlayer(
    playback: OnlinePlaybackBundle,
    playbackSession: PlaybackSession,
    onPlaybackSessionEvent: (PlaybackSessionEvent) -> Unit,
    variant: PlaybackVariant,
    softRetryGeneration: Int,
    playerRebuildGeneration: Int,
    initialPositionMs: Long,
    initialPlayWhenReady: Boolean,
    speed: Float,
    equalizer: PlayerEqualizerController,
    defaultSubtitlesEnabled: Boolean,
    anime4kEnabled: Boolean,
    onAnime4kFailure: () -> Unit,
    skipSettings: PlayerSkipSettings,
    showSkipDialog: Boolean,
    onDismissSkipDialog: () -> Unit,
    onSkipSettingsChanged: (PlayerSkipSettings) -> Unit,
    onUseProviderSkipData: (() -> Unit)? = null,
    onDisableProviderSkipData: (() -> Unit)? = null,
    onPositionSaved: (Long, Long, Boolean) -> Unit,
    onEnded: () -> Unit,
    onError: (PlaybackFailure) -> Unit,
    videoScaleMode: VideoScaleMode,
    onSingleTap: () -> Unit,
    onPinchScale: (Int) -> Unit,
    onPlayerAvailable: (Player?) -> Unit,
    isInPictureInPictureMode: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val latestPlaybackSession by rememberUpdatedState(playbackSession)
    val latestAnime4kEnabled by rememberUpdatedState(anime4kEnabled)
    val latestOnAnime4kFailure by rememberUpdatedState(onAnime4kFailure)
    val latestOnError by rememberUpdatedState(onError)
    val episode = playback.episode
    val playbackPlan = playback.playbackPlan
    var endHandled by remember(episode.id, variant.key, playerRebuildGeneration) { mutableStateOf(false) }
    val player = remember(episode.id, variant.key, playerRebuildGeneration) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(KODIK_USER_AGENT)
            .setDefaultRequestProperties(variant.headers)
        val dataSourceFactory = if (variant.isLocal) {
            DefaultDataSource.Factory(context)
        } else {
            val onlineUpstream = PlaybackStreamCache.wrap(
                context = context,
                upstreamFactory = DefaultDataSource.Factory(context, httpFactory),
            )
            OfflineMediaCache.readThroughFactory(
                context = context,
                upstreamFactory = onlineUpstream,
                downloadId = variant.offlineCacheId,
            )
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                setVideoEffects(anime4kVideoEffects(anime4kEnabled))
                setMediaItem(variant.toMediaItem(episode.id))
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !defaultSubtitlesEnabled)
                    .build()
                if (initialPositionMs > 0L) seekTo(initialPositionMs)
                playWhenReady = initialPlayWhenReady
                prepare()
            }
    }

    LaunchedEffect(player, softRetryGeneration, variant.kind) {
        if (softRetryGeneration <= 0 || !PlaybackPlayerRetryPolicy.reusePlayerInstance(variant.kind)) {
            return@LaunchedEffect
        }
        endHandled = false
        val retryPosition = initialPositionMs.coerceAtLeast(0L)
        val shouldPlay = initialPlayWhenReady
        runCatching {
            if (retryPosition > 0L) player.seekTo(retryPosition)
            // Re-prepare the same Media3 instance: decoder, cache and media-session stay attached.
            player.prepare()
            player.playWhenReady = shouldPlay
        }.onFailure { error ->
            latestOnError(PlaybackFailureClassifier.classify(error))
        }
    }

    PlayerMediaSessionEffect(player, "online-${playback.providerId}-${episode.id}")

    LaunchedEffect(player, speed) {
        player.setPlaybackSpeed(speed)
    }

    LaunchedEffect(player, anime4kEnabled) {
        runCatching { player.setVideoEffects(anime4kVideoEffects(anime4kEnabled)) }
            .onFailure { latestOnAnime4kFailure() }
    }

    DisposableEffect(player) {
        onPlaybackSessionEvent(
            PlaybackSessionEvent.Prepare(
                episodeKey = playbackPlan.episodeKey,
                variantKey = variant.key,
                positionMs = initialPositionMs,
                durationMs = playbackPlan.progress.durationMs.takeIf { it > 0L } ?: episode.durationMs,
                speed = speed,
                playWhenReady = initialPlayWhenReady,
            ),
        )
        val sessionBridge = Media3PlaybackSessionBridge(
            coroutineScope = coroutineScope,
            currentSession = { latestPlaybackSession },
            dispatch = onPlaybackSessionEvent,
            fallbackDurationMs = { episode.durationMs },
        ).also { it.attach(player) }
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
                            PlaybackFailure(
                                kind = PlaybackFailureKind.NETWORK,
                                detail = "Поток завершился раньше ожидаемого. Прогресс сохранён; " +
                                    "плеер попробует восстановить соединение или другое зеркало.",
                            ),
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    PLAYER_LOG_TAG,
                    "Media3: ${error.errorCodeName}; url=${variant.uri.substringBefore('?').substringBefore('#')}",
                    error,
                )
                if (latestAnime4kEnabled && error.isAnime4kVideoProcessingFailure()) {
                    val position = player.currentPosition.coerceAtLeast(0L)
                    val shouldPlay = player.playWhenReady
                    latestOnAnime4kFailure()
                    runCatching {
                        player.setVideoEffects(anime4kVideoEffects(false))
                        if (position > 0L) player.seekTo(position)
                        player.prepare()
                        player.playWhenReady = shouldPlay
                    }
                    return
                }
                onError(PlaybackFailureClassifier.classify(error))
            }
        }
        player.addListener(listener)
        equalizer.attach(player.audioSessionId)
        onDispose {
            sessionBridge.detach()
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
        val stallDetector = PlaybackStallDetector()
        while (isActive) {
            delay(1_000L)
            val expectsProgress = player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            if (stallDetector.observe(
                    nowMs = SystemClock.elapsedRealtime(),
                    positionMs = player.currentPosition,
                    expectsProgress = expectsProgress,
                )
            ) {
                Log.w(
                    PLAYER_LOG_TAG,
                    "Playback stalled; variant=${variant.displayName}; position=${player.currentPosition}",
                )
                latestOnError(
                    PlaybackFailure(
                        kind = PlaybackFailureKind.TIMEOUT,
                        detail = "Поток не продвигается более ${PlaybackStallDetector.DEFAULT_TIMEOUT_MS / 1_000} секунд",
                    ),
                )
                return@LaunchedEffect
            }
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

    var manualSkip by remember(player, skipSettings) { mutableStateOf<ManualSkipAction?>(null) }
    LaunchedEffect(player, skipSettings) {
        while (isActive) {
            manualSkip = manualSkipAction(
                settings = skipSettings,
                positionMs = player.currentPosition,
                durationMs = player.safeOnlineDuration(episode.durationMs),
            )
            delay(250L)
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

    manualSkip?.takeIf { !isInPictureInPictureMode }?.let { action ->
        Surface(
            onClick = { player.seekTo(action.targetMs); manualSkip = null },
            modifier = Modifier.padding(18.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.74f),
            contentColor = Color.White,
        ) {
            Text(action.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }

    if (showSkipDialog && !isInPictureInPictureMode) {
        SkipSettingsSheet(
            settings = skipSettings,
            currentPositionMs = { player.currentPosition.coerceAtLeast(0L) },
            durationMs = { player.safeOnlineDuration(episode.durationMs) },
            onDismiss = onDismissSkipDialog,
            onSave = onSkipSettingsChanged,
            onUseProviderData = onUseProviderSkipData,
            onDisableProviderData = onDisableProviderSkipData,
        )
    }
}

private fun PlaybackVariant.toMediaItem(episodeId: String): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setMediaId(episodeId)
    .setMimeType(
        when {
            uri.startsWith("file:") && uri.substringBefore('?').endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
            uri.startsWith("file:") && uri.substringBefore('?').endsWith(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            uri.startsWith("content:") -> null
            else -> when (kind) {
            PlaybackVariantKind.HLS -> MimeTypes.APPLICATION_M3U8
            PlaybackVariantKind.MP4 -> MimeTypes.VIDEO_MP4
            PlaybackVariantKind.LOCAL,
            PlaybackVariantKind.EMBED,
            PlaybackVariantKind.EXTERNAL -> null
            }
        },
    )
    .build()

private fun Player.safeOnlineDuration(fallbackMs: Long): Long =
    duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: fallbackMs

internal fun selectFallbackStream(
    streams: List<OnlineStream>,
    current: OnlineStream,
    failedStreamKeys: Set<String>,
    failure: PlaybackFailure? = null,
): OnlineStream? = OnlineStreamResolver.selectFallback(
    streams = streams,
    current = current,
    failedStreamKeys = failedStreamKeys,
    failure = failure,
)

internal fun OnlineStream.failureKey(): String = OnlineStreamResolver.failureKey(this)

internal fun OnlineStream.streamRefreshIdentity(ownerProviderId: String? = null): String =
    toMediaTransportSource(
        ownerProviderId = ownerProviderId?.takeIf(String::isNotBlank) ?: providerId.orEmpty(),
        ownerProviderName = providerName,
    ).stableRefreshIdentity

internal fun OnlineStream.streamHostFamily(): String? =
    toMediaTransportSource(
        ownerProviderId = providerId.orEmpty(),
        ownerProviderName = providerName,
    ).normalizedRouteFamily

/** Signed/temporary streams are re-resolved once before abandoning the variant. */
internal fun shouldRefreshExpiredOnlineStream(
    failure: PlaybackFailure,
    stream: OnlineStream?,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    stream ?: return false
    val source = stream.toMediaTransportSource(
        ownerProviderId = stream.providerId.orEmpty(),
        ownerProviderName = stream.providerName,
    )
    return TransportFailurePolicy.shouldRefresh(
        source = source,
        kind = failure.kind.toTransportFailureKind(),
        nowMs = nowMs,
    )
}

/** Compatibility name kept for existing tests/callers while refresh is now provider-neutral. */
internal fun shouldRefreshExpiredAnimetkaStream(
    failure: PlaybackFailure,
    stream: OnlineStream?,
): Boolean = stream?.providerId == OnlineProviderIds.ANIMETKA &&
    shouldRefreshExpiredOnlineStream(failure, stream)


internal fun isCredibleOnlineCompletion(positionMs: Long, durationMs: Long): Boolean =
    PlaybackCompletionPolicy.isCredibleNaturalEnd(positionMs, durationMs)
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
