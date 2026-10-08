package com.sergey.animevault.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

private val SignatureViolet = Color(0xFFA78BFA)
private val SignatureWhite = Color(0xFFF5F5F7)
private val SignatureWordmark = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 4.sp,
)

/** VA trace → white reveal → wordmark → home. Frame updates only invalidate drawing/layers. */
@Composable
fun AnimeVaultLaunchIntro(
    motionScale: Float,
    onFinished: () -> Unit,
    startAnimation: Boolean = true,
) {
    val finished = rememberUpdatedState(onFinished)
    val ready = rememberUpdatedState(startAnimation)
    var completed by remember { mutableStateOf(false) }
    val finish = remember {
        {
            if (!completed) {
                completed = true
                finished.value()
            }
        }
    }
    if (motionScale <= 0f) {
        LaunchedEffect(Unit) { finish() }
        return
    }

    val reduced = motionScale < 1f
    val initialTime = if (reduced) SignatureIntroTimeline.REDUCED_START_MS else 0f
    val elapsed = remember(reduced) { mutableFloatStateOf(initialTime) }
    val monogram = remember { SignatureMonogram() }
    LaunchedEffect(reduced) {
        playSignatureIntro(reduced, snapshotFlow { ready.value }) { elapsed.floatValue = it }
        finish()
    }

    BackHandler(onBack = finish)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { alpha = SignatureIntroTimeline.overlayAlpha(elapsed.floatValue) }
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { finish() }) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            monogram.update(size, density)
            monogram.draw(this, elapsed.floatValue)
        }
        Text(
            text = "ANIMEVAULT",
            color = SignatureWhite,
            style = SignatureWordmark,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.offset(y = minOf(56.dp, maxHeight * .13f))
                .graphicsLayer { alpha = SignatureIntroTimeline.wordmarkAlpha(elapsed.floatValue) },
        )
    }
}

/** Paths, measures and strokes are retained; geometry changes only with size/density. */
private class SignatureMonogram {
    private val v = Path()
    private val a = Path()
    private val bar = Path()
    private val visibleV = Path()
    private val visibleA = Path()
    private val visibleBar = Path()
    private val vMeasure = PathMeasure()
    private val aMeasure = PathMeasure()
    private val barMeasure = PathMeasure()
    private var cachedSize = Size.Unspecified
    private var cachedDensity = 0f
    private var ink = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private var halo = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun update(size: Size, density: Float) {
        if (cachedSize == size && cachedDensity == density) return
        cachedSize = size
        cachedDensity = density
        ink = Stroke(width = 2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        halo = Stroke(width = 5.6f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val extent = min(232f * density, min(size.width * .70f, size.height * .48f))
        val left = size.width * .5f - extent * .51f
        val top = size.height * .5f - min(36f * density, size.height * .08f) - extent * .505f
        // Same normalized VA geometry as VaultLogoMark, including its crossbar.
        v.rewind()
        v.moveTo(left + extent * .18f, top + extent * .28f)
        v.lineTo(left + extent * .39f, top + extent * .73f)
        v.lineTo(left + extent * .61f, top + extent * .28f)
        a.rewind()
        a.moveTo(left + extent * .43f, top + extent * .73f)
        a.lineTo(left + extent * .65f, top + extent * .28f)
        a.lineTo(left + extent * .84f, top + extent * .73f)
        bar.rewind()
        bar.moveTo(left + extent * .53f, top + extent * .56f)
        bar.lineTo(left + extent * .77f, top + extent * .56f)
        vMeasure.setPath(v, false)
        aMeasure.setPath(a, false)
        barMeasure.setPath(bar, false)
    }

    fun draw(scope: DrawScope, timeMs: Float) = with(scope) {
        val purpleAlpha = SignatureIntroTimeline.purpleAlpha(timeMs)
        val whiteAlpha = SignatureIntroTimeline.whiteAlpha(timeMs)
        val vPath = trim(v, visibleV, vMeasure, SignatureIntroTimeline.purpleTrace(timeMs))
        val aPath = trim(a, visibleA, aMeasure, SignatureIntroTimeline.aTrace(timeMs))
        val barPath = trim(bar, visibleBar, barMeasure, SignatureIntroTimeline.crossbarTrace(timeMs))
        // A very faint wider stroke gives a restrained halo without offscreen blur passes.
        drawPath(vPath, SignatureViolet, alpha = .045f * purpleAlpha, style = halo)
        drawPath(vPath, SignatureViolet, alpha = purpleAlpha, style = ink)
        val outlineAlpha = .58f * (1f - whiteAlpha)
        drawPath(aPath, SignatureViolet, alpha = outlineAlpha, style = ink)
        drawPath(barPath, SignatureViolet, alpha = outlineAlpha, style = ink)
        drawPath(aPath, SignatureWhite, alpha = .025f * whiteAlpha, style = halo)
        drawPath(aPath, SignatureWhite, alpha = whiteAlpha, style = ink)
        drawPath(barPath, SignatureWhite, alpha = whiteAlpha, style = ink)
    }

    private fun trim(path: Path, visible: Path, measure: PathMeasure, fraction: Float): Path {
        if (fraction >= 1f) return path
        visible.rewind()
        if (fraction > 0f) measure.getSegment(0f, measure.length * fraction, visible, true)
        return visible
    }
}
