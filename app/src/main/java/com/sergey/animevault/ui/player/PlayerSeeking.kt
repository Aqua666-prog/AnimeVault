package com.sergey.animevault.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class SeekFeedback(
    val headline: String,
    val targetPositionMs: Long? = null,
    val committed: Boolean = true,
    val token: Long = System.nanoTime(),
)

/**
 * Media3 owns decoding and the video surface while AnimeVault renders its own
 * transport chrome. The gesture layer adds double-tap seek, horizontal scrub,
 * brightness/volume control and pinch-driven viewport scaling.
 */
@Composable
internal fun PlayerSurface(
    modifier: Modifier,
    player: Player,
    showController: Boolean = false,
    previewUri: Uri? = null,
    videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    onSingleTap: () -> Unit = {},
    onPinchScale: (Int) -> Unit = {},
    onControllerVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var feedback by remember(player) { mutableStateOf<SeekFeedback?>(null) }
    var previewFrame by remember(player, previewUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val previewController = remember(previewUri) {
        previewUri?.let { SeekPreviewController(context.applicationContext, it) }
    }
    val latestFeedbackCallback by rememberUpdatedState<(SeekFeedback) -> Unit> { value ->
        feedback = value
    }
    val latestControllerVisibilityCallback by rememberUpdatedState(onControllerVisibilityChanged)
    val latestSingleTapCallback by rememberUpdatedState(onSingleTap)
    val latestPinchScaleCallback by rememberUpdatedState(onPinchScale)

    DisposableEffect(previewController) {
        onDispose { previewController?.release() }
    }

    LaunchedEffect(feedback?.targetPositionMs, feedback?.committed, previewController) {
        val current = feedback
        val target = current?.targetPositionMs
        if (current == null || current.committed || target == null || previewController == null) {
            previewFrame = null
            return@LaunchedEffect
        }
        // Scrubbing emits many MOVE events. A short debounce keeps frame extraction cheap.
        delay(SEEK_PREVIEW_DEBOUNCE_MS)
        previewFrame = withContext(Dispatchers.IO) { previewController.frameAt(target) }
    }

    LaunchedEffect(feedback?.token) {
        val current = feedback ?: return@LaunchedEffect
        if (current.committed) {
            delay(SEEK_FEEDBACK_DURATION_MS)
            if (feedback?.token == current.token) feedback = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    keepScreenOn = true
                    useController = showController
                    controllerAutoShow = showController
                    controllerHideOnTouch = true
                    controllerShowTimeoutMs = 4_000
                    resizeMode = videoScaleMode.toMedia3ResizeMode()
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowSubtitleButton(true)
                    this.player = player
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            latestControllerVisibilityCallback(visibility == View.VISIBLE)
                        },
                    )
                    setOnTouchListener(
                        PlayerGestureDetector(
                            view = this,
                            player = { this.player },
                            onFeedback = { latestFeedbackCallback(it) },
                            onSingleTap = { latestSingleTapCallback() },
                            onPinchScale = { latestPinchScaleCallback(it) },
                        ),
                    )
                }
            },
            update = { view ->
                view.player = player
                view.keepScreenOn = true
                view.useController = showController
                view.controllerAutoShow = showController
                view.resizeMode = videoScaleMode.toMedia3ResizeMode()
                if (!showController) latestControllerVisibilityCallback(false)
            },
        )

        feedback?.let { value ->
            val targetPosition = value.targetPositionMs
            val durationMs = player.validDuration()
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 360.dp),
                color = Color.Black.copy(alpha = 0.82f),
                contentColor = Color.White,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.12f),
                ),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!value.committed && previewFrame != null) {
                        Image(
                            bitmap = previewFrame!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                                .background(Color.Black, RoundedCornerShape(15.dp)),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = value.headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        targetPosition?.let { target ->
                            Text(
                                text = if (durationMs > 0L) {
                                    "${formatPlayerTime(target)}  /  ${formatPlayerTime(durationMs)}"
                                } else {
                                    formatPlayerTime(target)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f),
                            )
                            if (!value.committed && durationMs > 0L) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 9.dp)
                                        .height(3.dp)
                                        .background(
                                            Color.White.copy(alpha = 0.14f),
                                            RoundedCornerShape(50),
                                        ),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(
                                                (target.toFloat() / durationMs.toFloat())
                                                    .coerceIn(0f, 1f),
                                            )
                                            .height(3.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(50),
                                            ),
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

/** Handles AnimeVault playback gestures on top of the Media3 video surface. */
private class PlayerGestureDetector(
    private val view: View,
    private val player: () -> Player?,
    private val onFeedback: (SeekFeedback) -> Unit,
    private val onSingleTap: () -> Unit,
    private val onPinchScale: (Int) -> Unit,
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val activity = view.context.findActivity()
    private val audioManager = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var downX = 0f
    private var downY = 0f
    private var startPositionMs = 0L
    private var pendingPositionMs = 0L
    private var gestureMode = GestureMode.UNDECIDED
    private var verticalControl = VerticalControl.BRIGHTNESS
    private var startVerticalLevel = 0.5f
    private var pendingVerticalLevel = 0.5f
    private var gestureZone = false
    private var doubleTapConsumed = false
    private var pinchFactor = 1f
    private var pinchConsumed = false

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchFactor = 1f
                pinchConsumed = true
                gestureMode = GestureMode.UNDECIDED
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchFactor *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                when {
                    pinchFactor >= 1.10f -> onPinchScale(1)
                    pinchFactor <= 0.90f -> onPinchScale(-1)
                }
                pinchFactor = 1f
            }
        },
    )

    private val taps = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                onSingleTap()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (event.y > view.height * ACTIVE_GESTURE_HEIGHT_FRACTION) return false
                val currentPlayer = player() ?: return false
                doubleTapConsumed = true
                if (!currentPlayer.isCurrentMediaItemSeekable) {
                    onFeedback(SeekFeedback("Перемотка недоступна для этого потока"))
                    return true
                }
                val backward = event.x < view.width / 2f
                val before = currentPlayer.currentPosition.coerceAtLeast(0L)
                if (backward) currentPlayer.seekBack() else currentPlayer.seekForward()
                val target = predictedStepPosition(
                    player = currentPlayer,
                    beforeMs = before,
                    deltaMs = if (backward) -SEEK_BACK_MS else SEEK_FORWARD_MS,
                )
                onFeedback(
                    SeekFeedback(
                        headline = if (backward) "−10 секунд" else "+15 секунд",
                        targetPositionMs = target,
                    ),
                )
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(ignored: View, event: MotionEvent): Boolean {
        doubleTapConsumed = false
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1 || pinchConsumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinchConsumed = false
            }
            resetGesture()
            return true
        }
        taps.onTouchEvent(event)
        if (doubleTapConsumed) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startPositionMs = player()?.currentPosition?.coerceAtLeast(0L) ?: 0L
                pendingPositionMs = startPositionMs
                gestureMode = GestureMode.UNDECIDED
                gestureZone = event.y <= view.height * ACTIVE_GESTURE_HEIGHT_FRACTION
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureZone) return false
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (gestureMode == GestureMode.UNDECIDED) {
                    gestureMode = when {
                        abs(deltaX) > touchSlop * 1.5f && abs(deltaX) > abs(deltaY) * 1.25f -> {
                            GestureMode.SEEK
                        }
                        abs(deltaY) > touchSlop * 1.5f && abs(deltaY) > abs(deltaX) * 1.25f -> {
                            verticalControl = if (downX < view.width / 2f) {
                                VerticalControl.BRIGHTNESS
                            } else {
                                VerticalControl.VOLUME
                            }
                            startVerticalLevel = readVerticalLevel(verticalControl)
                            pendingVerticalLevel = startVerticalLevel
                            GestureMode.VERTICAL
                        }
                        else -> GestureMode.UNDECIDED
                    }
                }

                when (gestureMode) {
                    GestureMode.SEEK -> {
                        val currentPlayer = player() ?: return true
                        val durationMs = currentPlayer.validDuration()
                        if (!currentPlayer.isCurrentMediaItemSeekable || durationMs <= 0L) {
                            onFeedback(
                                SeekFeedback(
                                    headline = "Перемотка недоступна для этого потока",
                                    committed = false,
                                ),
                            )
                            return true
                        }
                        pendingPositionMs = calculateSwipeSeekTarget(
                            startPositionMs = startPositionMs,
                            durationMs = durationMs,
                            dragFraction = deltaX / view.width.coerceAtLeast(1),
                        )
                        onFeedback(
                            SeekFeedback(
                                headline = "Перемотка ${formatSignedTime(pendingPositionMs - startPositionMs)}",
                                targetPositionMs = pendingPositionMs,
                                committed = false,
                            ),
                        )
                        return true
                    }

                    GestureMode.VERTICAL -> {
                        pendingVerticalLevel = calculateVerticalGestureLevel(
                            startLevel = startVerticalLevel,
                            dragFraction = deltaY / view.height.coerceAtLeast(1),
                        )
                        applyVerticalLevel(verticalControl, pendingVerticalLevel)
                        onFeedback(
                            SeekFeedback(
                                headline = verticalFeedback(verticalControl, pendingVerticalLevel),
                                committed = false,
                            ),
                        )
                        return true
                    }

                    GestureMode.UNDECIDED -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                when (gestureMode) {
                    GestureMode.SEEK -> {
                        val currentPlayer = player()
                        if (
                            currentPlayer != null &&
                            currentPlayer.isCurrentMediaItemSeekable &&
                            currentPlayer.validDuration() > 0L
                        ) {
                            currentPlayer.seekTo(pendingPositionMs)
                            onFeedback(
                                SeekFeedback(
                                    headline = "Перемотано ${formatSignedTime(pendingPositionMs - startPositionMs)}",
                                    targetPositionMs = pendingPositionMs,
                                ),
                            )
                        } else {
                            onFeedback(SeekFeedback("Перемотка недоступна для этого потока"))
                        }
                        resetGesture()
                        return true
                    }

                    GestureMode.VERTICAL -> {
                        onFeedback(
                            SeekFeedback(
                                headline = verticalFeedback(verticalControl, pendingVerticalLevel),
                            ),
                        )
                        resetGesture()
                        return true
                    }

                    GestureMode.UNDECIDED -> resetGesture()
                }
            }

            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return false
    }

    private fun readVerticalLevel(control: VerticalControl): Float = when (control) {
        VerticalControl.VOLUME -> {
            val manager = audioManager ?: return 0.5f
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
        }
        VerticalControl.BRIGHTNESS -> {
            val windowLevel = activity?.window?.attributes?.screenBrightness ?: -1f
            if (windowLevel >= 0f) {
                windowLevel.coerceIn(MIN_BRIGHTNESS_LEVEL, 1f)
            } else {
                runCatching {
                    Settings.System.getInt(view.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        .toFloat() / 255f
                }.getOrDefault(0.5f).coerceIn(MIN_BRIGHTNESS_LEVEL, 1f)
            }
        }
    }

    private fun applyVerticalLevel(control: VerticalControl, level: Float) {
        when (control) {
            VerticalControl.VOLUME -> {
                val manager = audioManager ?: return
                val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val target = (level.coerceIn(0f, 1f) * maximum).roundToInt().coerceIn(0, maximum)
                runCatching {
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                }
            }
            VerticalControl.BRIGHTNESS -> {
                val window = activity?.window ?: return
                runCatching {
                    val attributes = window.attributes
                    attributes.screenBrightness = level.coerceIn(MIN_BRIGHTNESS_LEVEL, 1f)
                    window.attributes = attributes
                }
            }
        }
    }

    private fun resetGesture() {
        gestureMode = GestureMode.UNDECIDED
        gestureZone = false
    }
}


/**
 * Lazily extracts a representative frame for local SAF media while scrubbing.
 * Failures are sticky for the session so unsupported codecs/providers do not
 * repeatedly hit MediaMetadataRetriever.
 */
private class SeekPreviewController(
    private val context: Context,
    private val uri: Uri,
) {
    private var retriever: MediaMetadataRetriever? = null
    private var unavailable = false
    private var cachedBucketMs = Long.MIN_VALUE
    private var cachedFrame: android.graphics.Bitmap? = null

    @Synchronized
    fun frameAt(positionMs: Long): android.graphics.Bitmap? {
        if (unavailable) return null
        val bucketMs = seekPreviewBucket(positionMs)
        if (bucketMs == cachedBucketMs) return cachedFrame
        return runCatching {
            val active = retriever ?: MediaMetadataRetriever().also { created ->
                created.setDataSource(context, uri)
                retriever = created
            }
            val frame = active.getFrameAtTime(
                bucketMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            cachedBucketMs = bucketMs
            cachedFrame = frame
            frame
        }.getOrElse {
            unavailable = true
            null
        }
    }

    @Synchronized
    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        cachedFrame = null
    }
}

private enum class GestureMode {
    UNDECIDED,
    SEEK,
    VERTICAL,
}

private enum class VerticalControl {
    BRIGHTNESS,
    VOLUME,
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun verticalFeedback(control: VerticalControl, level: Float): String {
    val percent = (level.coerceIn(0f, 1f) * 100).roundToInt()
    return when (control) {
        VerticalControl.BRIGHTNESS -> "Яркость $percent%"
        VerticalControl.VOLUME -> "Громкость $percent%"
    }
}

private fun predictedStepPosition(player: Player, beforeMs: Long, deltaMs: Long): Long {
    val durationMs = player.validDuration()
    return clampSeekPosition(beforeMs + deltaMs, durationMs)
}

internal fun calculateSwipeSeekTarget(
    startPositionMs: Long,
    durationMs: Long,
    dragFraction: Float,
): Long {
    if (durationMs <= 0L) return startPositionMs.coerceAtLeast(0L)
    val fullWidthTravelMs = min(
        MAX_SWIPE_TRAVEL_MS,
        max(MIN_SWIPE_TRAVEL_MS, durationMs / 4),
    )
    val deltaMs = (dragFraction.coerceIn(-1f, 1f) * fullWidthTravelMs).roundToLong()
    return clampSeekPosition(startPositionMs + deltaMs, durationMs)
}

internal fun seekPreviewBucket(positionMs: Long): Long =
    (positionMs.coerceAtLeast(0L) / SEEK_PREVIEW_BUCKET_MS) * SEEK_PREVIEW_BUCKET_MS

internal fun calculateVerticalGestureLevel(startLevel: Float, dragFraction: Float): Float =
    (startLevel - dragFraction * VERTICAL_GESTURE_SENSITIVITY).coerceIn(0f, 1f)

internal fun clampSeekPosition(requestedMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) requestedMs.coerceIn(0L, durationMs) else requestedMs.coerceAtLeast(0L)

private fun Player.validDuration(): Long = duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L

private fun formatSignedTime(deltaMs: Long): String = buildString {
    append(if (deltaMs < 0L) '−' else '+')
    append(formatPlayerTime(abs(deltaMs)))
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun VideoScaleMode.toMedia3ResizeMode(): Int = when (this) {
    VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

internal const val SEEK_BACK_MS = 10_000L
internal const val SEEK_FORWARD_MS = 15_000L
private const val ACTIVE_GESTURE_HEIGHT_FRACTION = 0.72f
private const val MIN_SWIPE_TRAVEL_MS = 60_000L
private const val MAX_SWIPE_TRAVEL_MS = 300_000L
private const val SEEK_FEEDBACK_DURATION_MS = 850L
private const val SEEK_PREVIEW_DEBOUNCE_MS = 110L
private const val SEEK_PREVIEW_BUCKET_MS = 5_000L
private const val VERTICAL_GESTURE_SENSITIVITY = 1.15f
private const val MIN_BRIGHTNESS_LEVEL = 0.02f
