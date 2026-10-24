package com.sergey.animevault.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sin
import kotlin.math.pow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sergey.animevault.R
import com.sergey.animevault.ui.components.AnimeVaultBrandImage

private const val EntranceDurationMillis = 2100

/** A single finite timeline over the existing launch gate. No navigation or network work. */
@Composable
fun AnimeVaultLaunchIntro(motionScale: Float, onFinished: () -> Unit) {
    val finished = rememberUpdatedState(onFinished)
    // Nonessential effects are skipped for every reduced/off app or system motion mode.
    if (motionScale < 1f) {
        LaunchedEffect(Unit) { finished.value() }
        return
    }
    val elapsed = remember { Animatable(0f) }
    var artworkReady by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Do not resume an unfinished entrance after returning from the background.
            if (event == Lifecycle.Event.ON_STOP) finished.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        // Give the local cached decode a bounded head start, never wait indefinitely.
        withTimeoutOrNull(250L) { snapshotFlow { artworkReady }.first { it } }
        elapsed.animateTo(EntranceDurationMillis.toFloat(), tween(EntranceDurationMillis, easing = LinearEasing))
        finished.value()
    }
    BackHandler { finished.value() }
    val background = colorResource(R.color.animevault_icon_background)
    val violet = colorResource(R.color.animevault_brand_violet)
    val lemon = colorResource(R.color.animevault_brand_lemon)
    val ivory = colorResource(R.color.animevault_brand_ivory)
    val skipDescription = stringResource(R.string.launch_skip_intro)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - phase(elapsed.value, 1630f, 2100f) }
            .background(background)
            .clickable(role = Role.Button, onClick = { finished.value() })
            .semantics { contentDescription = skipDescription },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            val artworkSize = minOf(maxWidth * .86f, maxHeight * .72f, 340.dp)
            Canvas(Modifier.fillMaxSize()) {
                val time = elapsed.value
                val center = Offset(size.width / 2f, size.height / 2f)
                val unit = artworkSize.toPx().coerceAtLeast(1f)
                val light = phase(time, 0f, 650f) * (1f - phase(time, 1530f, 1950f))
                val arrival = settle(time, 150f, 1200f)
                // Large, soft pools of light give depth without a blur pass or a bitmap backdrop.
                for (side in listOf(-1f, 1f)) {
                    val glowCenter = center + Offset(side * unit * (.55f - .12f * arrival), -unit * .18f)
                    drawCircle(
                        Brush.radialGradient(listOf(violet.copy(alpha = light * .19f), Color.Transparent),
                            center = glowCenter, radius = unit * .85f),
                        radius = unit * .85f, center = glowCenter,
                    )
                }
                // Tilted orbital ellipses sit behind the portrait, like a restrained title sequence.
                rotate(-24f + arrival * 7f, center) {
                    for (orbit in 0..1) {
                        val width = unit * (1.44f + orbit * .22f)
                        val height = unit * (.70f + orbit * .17f)
                        val top = center - Offset(width / 2f, height / 2f)
                        drawOval(violet.copy(alpha = light * .16f), top, Size(width, height),
                            style = Stroke(.7.dp.toPx()))
                        drawArc(lemon.copy(alpha = light * .38f),
                            startAngle = 200f + orbit * 155f + arrival * 32f, sweepAngle = 22f,
                            useCenter = false, topLeft = top, size = Size(width, height),
                            style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                // Fixed seeds: fourteen quiet motes, no random allocations or endless particle engine.
                for (index in 0 until 14) {
                    val seed = index * 2.39996f
                    val x = center.x + sin(seed) * unit * (.52f + (index % 3) * .10f)
                    val y = center.y + sin(seed * 1.7f) * unit * .68f - arrival * 12.dp.toPx()
                    val opacity = light * (.12f + .14f * sin(seed).let { it * it })
                    drawCircle(if (index % 4 == 0) lemon.copy(alpha = opacity) else ivory.copy(alpha = opacity),
                        radius = if (index % 3 == 0) 1.dp.toPx() else .6.dp.toPx(), center = Offset(x, y))
                }
                // The frame resolves inward and then recedes before the app is revealed.
                val frame = phase(time, 220f, 800f) * (1f - phase(time, 1420f, 1800f))
                val distance = unit * (.54f + .08f * (1f - arrival))
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                    val corner = center + Offset(x * distance, y * distance)
                    val ink = violet.copy(alpha = frame * .4f)
                    drawLine(ink, corner, corner - Offset(x * unit * .055f, 0f), .8.dp.toPx())
                    drawLine(ink, corner, corner - Offset(0f, y * unit * .055f), .8.dp.toPx())
                }
                val accent = phase(time, 800f, 1300f) * (1f - phase(time, 1530f, 1900f))
                val halfLine = unit * .17f * settle(time, 800f, 1300f)
                val lineY = center.y + unit * .59f
                if (halfLine > 0f) drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, lemon.copy(alpha = accent * .65f), Color.Transparent),
                        startX = center.x - halfLine, endX = center.x + halfLine),
                    Offset(center.x - halfLine, lineY), Offset(center.x + halfLine, lineY), .8.dp.toPx(),
                )
            }
            // One approved image from beginning to end: no second face, crop, or imitation lettering.
            AnimeVaultBrandImage(
                decodeSize = 768,
                contentDescription = null,
                onReady = { artworkReady = true },
                modifier = Modifier.align(Alignment.Center).size(artworkSize)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        val arrival = settle(elapsed.value, 180f, 1150f)
                        alpha = phase(elapsed.value, 180f, 500f)
                        scaleX = 1.065f - .065f * arrival
                        scaleY = scaleX
                        translationY = 14.dp.toPx() * (1f - arrival)
                    }
                    .drawWithContent {
                        drawContent()
                        // Feathered curtains open from the centre; only the image is masked.
                        val opening = settle(elapsed.value, 180f, 1000f)
                        if (opening < 1f) {
                            val edge = size.width * .5f * (1f - opening)
                            val feather = (18.dp.toPx() * (1f - opening)).coerceAtLeast(.1f)
                            drawRect(Color.Black, size = Size(edge, size.height), blendMode = BlendMode.DstOut)
                            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                                startX = edge, endX = edge + feather),
                                topLeft = Offset(edge, 0f), size = Size(feather, size.height), blendMode = BlendMode.DstOut)
                            drawRect(Color.Black, topLeft = Offset(size.width - edge, 0f),
                                size = Size(edge, size.height), blendMode = BlendMode.DstOut)
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black),
                                startX = size.width - edge - feather, endX = size.width - edge),
                                topLeft = Offset(size.width - edge - feather, 0f), size = Size(feather, size.height), blendMode = BlendMode.DstOut)
                        }
                    },
            )
            if (maxHeight > 280.dp && LocalDensity.current.fontScale <= 1.3f) Text(
                text = "ENTER THE VAULT", color = ivory.copy(alpha = .55f),
                fontSize = 9.sp, letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = artworkSize * .66f)
                    .graphicsLayer {
                        alpha = phase(elapsed.value, 1000f, 1400f) * (1f - phase(elapsed.value, 1530f, 1800f))
                        translationY = 5.dp.toPx() * (1f - settle(elapsed.value, 1000f, 1400f))
                    },
            )
        }
    }
}

/** Smoothstep: continuous velocity at the boundaries of each phase. */
private fun phase(time: Float, start: Float, end: Float): Float {
    val fraction = ((time - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

/** Quintic ease-out lets the composition land once instead of drifting throughout the hold. */
private fun settle(time: Float, start: Float, end: Float): Float {
    val fraction = ((time - start) / (end - start)).coerceIn(0f, 1f)
    return 1f - (1f - fraction).pow(5)
}
