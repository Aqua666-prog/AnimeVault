package com.sergey.animevault.ui.clips

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ClipFeedRoute(
    viewModel: ClipFeedViewModel,
    onBack: () -> Unit,
    onOpenTitle: (String, String) -> Unit,
    onPlayEpisode: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ClipFeedEvent.Play -> onPlayEpisode(
                    event.providerId,
                    event.releaseId,
                    event.episodeId,
                )
                is ClipFeedEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    ClipFeedScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRetry = viewModel::retry,
        onPageSelected = viewModel::onPageSelected,
        onWatchDuration = viewModel::recordWatch,
        onToggleFavorite = viewModel::toggleFavorite,
        onNotInterested = viewModel::notInterested,
        onPlay = viewModel::play,
        onOpenTitle = { item ->
            viewModel.recordOpened(item)
            onOpenTitle(item.providerId, item.releaseId)
        },
    )
}

@Composable
fun ClipFeedScreen(
    uiState: ClipFeedUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onWatchDuration: (ClipFeedItem, Long) -> Unit,
    onToggleFavorite: (ClipFeedItem) -> Unit,
    onNotInterested: (ClipFeedItem) -> Unit,
    onPlay: (ClipFeedItem) -> Unit,
    onOpenTitle: (ClipFeedItem) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                ClipFeedLoading()
            }

            uiState.clips.isEmpty() -> {
                ClipFeedError(
                    message = uiState.errorMessage ?: "В ленте пока нет тайтлов",
                    onBack = onBack,
                    onRetry = onRetry,
                )
            }

            else -> {
                ClipPager(
                    clips = uiState.clips,
                    favoriteKeys = uiState.favoriteKeys,
                    onBack = onBack,
                    onPageSelected = onPageSelected,
                    onWatchDuration = onWatchDuration,
                    onToggleFavorite = onToggleFavorite,
                    onNotInterested = onNotInterested,
                    onPlay = onPlay,
                    onOpenTitle = onOpenTitle,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        )
    }
}

@Composable
private fun ClipPager(
    clips: List<ClipFeedItem>,
    favoriteKeys: Set<String>,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onWatchDuration: (ClipFeedItem, Long) -> Unit,
    onToggleFavorite: (ClipFeedItem) -> Unit,
    onNotInterested: (ClipFeedItem) -> Unit,
    onPlay: (ClipFeedItem) -> Unit,
    onOpenTitle: (ClipFeedItem) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { clips.size })
    val controller = remember(context) { ClipPlayerController(context) }
    var activePlayer by remember(controller) { mutableStateOf(controller.currentPlayer) }
    var muted by rememberSaveable { mutableStateOf(true) }
    var userPaused by remember { mutableStateOf(false) }
    var trackedPage by remember { mutableIntStateOf(0) }
    var pageStartedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showSwipeHint by rememberSaveable { mutableStateOf(true) }
    var activeYoutubeView by remember { mutableStateOf<WebView?>(null) }
    val latestYoutubeView by rememberUpdatedState(activeYoutubeView)
    val latestUserPaused by rememberUpdatedState(userPaused)

    val currentClip = clips.getOrNull(pagerState.currentPage)
    val nextClip = clips.getOrNull(pagerState.currentPage + 1)

    LaunchedEffect(
        currentClip?.playbackRequest,
        currentClip?.youtubeVideoId,
        nextClip?.playbackRequest,
        muted,
    ) {
        userPaused = false
        activePlayer = controller.play(currentClip?.playbackRequest, muted)
        controller.preload(nextClip?.playbackRequest)
        activeYoutubeView?.let { view ->
            sendYoutubeCommand(view, if (muted) "mute" else "unMute")
        }
    }

    LaunchedEffect(clips.size) {
        if (clips.isNotEmpty() && pagerState.currentPage > clips.lastIndex) {
            pagerState.scrollToPage(clips.lastIndex)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val now = SystemClock.elapsedRealtime()
                if (page != trackedPage) {
                    clips.getOrNull(trackedPage)?.let { previous ->
                        onWatchDuration(previous, now - pageStartedAt)
                    }
                    trackedPage = page
                    pageStartedAt = now
                    showSwipeHint = false
                }
                onPageSelected(page)
            }
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!latestUserPaused) {
                    controller.resume()
                    latestYoutubeView?.let { sendYoutubeCommand(it, "playVideo") }
                }
                Lifecycle.Event.ON_STOP -> {
                    controller.pause()
                    latestYoutubeView?.let { sendYoutubeCommand(it, "pauseVideo") }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            val now = SystemClock.elapsedRealtime()
            clips.getOrNull(trackedPage)?.let { item ->
                onWatchDuration(item, now - pageStartedAt)
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> clips[index].stableKey },
            beyondViewportPageCount = 1,
        ) { page ->
            val item = clips[page]
            val isActive = page == pagerState.currentPage
            ClipPage(
                item = item,
                player = activePlayer,
                isActive = isActive,
                isFavorite = item.stableKey in favoriteKeys,
                userPaused = isActive && userPaused,
                muted = muted,
                onYoutubeViewChanged = { view ->
                    if (isActive || view == null) activeYoutubeView = view
                },
                onToggleFavorite = { onToggleFavorite(item) },
                onNotInterested = {
                    onNotInterested(item)
                    val target = (pagerState.currentPage + 1).coerceAtMost(clips.lastIndex)
                    if (target != pagerState.currentPage) {
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                },
                onTogglePlayback = {
                    if (!item.youtubeVideoId.isNullOrBlank()) {
                        userPaused = !userPaused
                        activeYoutubeView?.let { view ->
                            sendYoutubeCommand(view, if (userPaused) "pauseVideo" else "playVideo")
                        }
                    } else if (activePlayer.isPlaying) {
                        activePlayer.pause()
                        userPaused = true
                    } else if (activePlayer.mediaItemCount > 0) {
                        activePlayer.play()
                        userPaused = false
                    }
                },
                onPlay = { onPlay(item) },
                onOpenTitle = { onOpenTitle(item) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipOverlayIconButton(
                onClick = onBack,
                contentDescription = "Назад",
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color.Black.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${clips.size}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ClipOverlayIconButton(
                    onClick = {
                        muted = !muted
                        controller.setMuted(muted)
                        activeYoutubeView?.let { view ->
                            sendYoutubeCommand(view, if (muted) "mute" else "unMute")
                        }
                    },
                    contentDescription = if (muted) "Включить звук" else "Выключить звук",
                ) {
                    Icon(
                        if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSwipeHint && pagerState.currentPage == 0,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 66.dp),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.44f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "Свайпните вверх",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@Composable
private fun ClipPage(
    item: ClipFeedItem,
    player: ExoPlayer,
    isActive: Boolean,
    isFavorite: Boolean,
    userPaused: Boolean,
    muted: Boolean,
    onYoutubeViewChanged: (WebView?) -> Unit,
    onToggleFavorite: () -> Unit,
    onNotInterested: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPlay: () -> Unit,
    onOpenTitle: () -> Unit,
) {
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var progress by remember(item.stableKey) { mutableFloatStateOf(0f) }
    var heartBurst by remember(item.stableKey) { mutableIntStateOf(0) }
    var youtubeReady by remember(item.stableKey) { mutableStateOf(false) }

    DisposableEffect(player, isActive) {
        if (!isActive) return@DisposableEffect onDispose { }
        playbackState = player.playbackState
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, isActive, item.playbackRequest) {
        progress = 0f
        if (!isActive || item.playbackRequest == null) return@LaunchedEffect
        val clipDuration = (item.clipEndMs - item.clipStartMs).coerceAtLeast(1L)
        while (isActive) {
            progress = (player.currentPosition.toFloat() / clipDuration.toFloat()).coerceIn(0f, 1f)
            delay(120L)
        }
    }

    val videoReady = isActive &&
        item.videoUrl != null &&
        playbackState == Player.STATE_READY
    val videoAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (videoReady) 1f else 0f,
        animationSpec = tween(260),
        label = "clip-video-alpha",
    )

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (isActive) 1.04f else 1f
                    scaleY = if (isActive) 1.04f else 1f
                },
            contentScale = ContentScale.Crop,
        )

        if (isActive && item.videoUrl != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        this.player = player
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(videoAlpha),
            )
        } else if (isActive && !item.youtubeVideoId.isNullOrBlank()) {
            YouTubeClipWebView(
                videoId = item.youtubeVideoId,
                muted = muted,
                onReady = { youtubeReady = true },
                onViewChanged = onYoutubeViewChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.22f),
                            0.40f to Color.Transparent,
                            0.70f to Color.Black.copy(alpha = 0.30f),
                            1f to Color.Black.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.stableKey, isFavorite, isActive) {
                    if (!isActive) return@pointerInput
                    detectTapGestures(
                        onTap = { onTogglePlayback() },
                        onDoubleTap = {
                            heartBurst += 1
                            if (!isFavorite) onToggleFavorite()
                        },
                    )
                },
        )

        when {
            isActive && !item.previewResolved -> {
                ClipCenterMessage(
                    loading = true,
                    text = "Ищем клип",
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            isActive && item.previewResolved && !item.hasVideo -> {
                ClipCenterMessage(
                    loading = true,
                    text = "Ищем другой клип",
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            isActive && !item.youtubeVideoId.isNullOrBlank() && !youtubeReady -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                )
            }

            isActive && item.videoUrl != null && playbackState == Player.STATE_BUFFERING -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                )
            }
        }

        if (isActive && userPaused && item.hasVideo) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.48f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Продолжить",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        ClipHeartBurst(
            trigger = heartBurst,
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(tween(220)) +
                slideInVertically(
                    initialOffsetY = { it / 5 },
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ),
            exit = fadeOut(tween(110)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                modifier = Modifier.padding(end = 12.dp, bottom = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ClipSideAction(
                    onClick = onToggleFavorite,
                    label = if (isFavorite) "В списке" else "В список",
                ) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                ClipSideAction(onClick = onOpenTitle, label = "Подробнее") {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.White)
                }
                ClipSideAction(onClick = onNotInterested, label = "Не моё") {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = null, tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(tween(220)) +
                slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                ),
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 78.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                item.clipLabel?.let { label ->
                    Text(
                        text = buildString {
                            append(item.clipSource ?: "Видео")
                            append(" · ")
                            append(label)
                            item.clipQualityLabel?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        item.year?.let { add(it.toString()) }
                        item.type?.takeIf(String::isNotBlank)?.let(::add)
                        addAll(item.genres.take(3))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Смотреть", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onOpenTitle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black.copy(alpha = 0.48f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Тайтл")
                    }
                }
                if (item.videoUrl != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.18f),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeClipWebView(
    videoId: String,
    muted: Boolean,
    onReady: () -> Unit,
    onViewChanged: (WebView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember(videoId) { mutableStateOf<WebView?>(null) }
    val html = remember(videoId, muted) { youtubeClipHtml(videoId, muted) }

    DisposableEffect(videoId) {
        onDispose {
            onViewChanged(null)
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        onReady()
                        sendYoutubeCommand(view, if (muted) "mute" else "unMute")
                        sendYoutubeCommand(view, "playVideo")
                    }
                }
                loadDataWithBaseURL(
                    "https://www.youtube-nocookie.com",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
                webView = this
                onViewChanged(this)
            }
        },
        update = { view ->
            webView = view
            onViewChanged(view)
            sendYoutubeCommand(view, if (muted) "mute" else "unMute")
        },
        modifier = modifier,
    )
}

private fun youtubeClipHtml(videoId: String, muted: Boolean): String = """
    <!doctype html>
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
      <style>
        html, body { margin:0; padding:0; width:100%; height:100%; overflow:hidden; background:#000; }
        iframe { position:fixed; inset:0; width:100%; height:100%; border:0; }
      </style>
    </head>
    <body>
      <iframe id="player"
        src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&mute=${if (muted) 1 else 0}&controls=0&playsinline=1&loop=1&playlist=$videoId&enablejsapi=1&rel=0"
        allow="autoplay; encrypted-media; picture-in-picture"
        allowfullscreen></iframe>
      <script>
        const player = document.getElementById('player');
        window.avCommand = function(func) {
          if (!player || !player.contentWindow) return;
          player.contentWindow.postMessage(JSON.stringify({event:'command', func:func, args:[]}), '*');
        };
      </script>
    </body>
    </html>
""".trimIndent()

private fun sendYoutubeCommand(view: WebView, command: String) {
    view.evaluateJavascript("window.avCommand && window.avCommand('$command');", null)
}

@Composable
private fun ClipHeartBurst(
    trigger: Int,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        scale.snapTo(0.55f)
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(90))
        scale.animateTo(1.28f, tween(180, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(90))
        delay(180)
        alpha.animateTo(0f, tween(180))
    }

    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .size(96.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
    )
}

@Composable
private fun ClipCenterMessage(
    loading: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.52f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ClipOverlayIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.42f),
        shape = CircleShape,
    ) {
        IconButton(onClick = onClick) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun ClipSideAction(
    onClick: () -> Unit,
    label: String,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.42f),
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun ClipFeedLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
            Text(
                text = "Собираем ленту",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ClipFeedError(
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding(),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Назад",
                tint = Color.White,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Повторить")
            }
        }
    }
}
