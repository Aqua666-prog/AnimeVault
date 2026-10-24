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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sergey.animevault.R
import com.sergey.animevault.ui.components.AnimeVaultBrandImage

private const val EntranceDurationMillis = 1750

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
        elapsed.animateTo(EntranceDurationMillis.toFloat(), tween(EntranceDurationMillis, easing = LinearEasing))
        finished.value()
    }
    BackHandler { finished.value() }
    val background = colorResource(R.color.animevault_icon_background)
    val violet = colorResource(R.color.animevault_brand_violet)
    val lemon = colorResource(R.color.animevault_brand_lemon)
    val skipDescription = stringResource(R.string.launch_skip_intro)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - phase(elapsed.value, 1350f, 1750f) }
            .background(background)
            .clickable(role = Role.Button, onClick = { finished.value() })
            .semantics { contentDescription = skipDescription },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            // A square fitted to both axes keeps the entire illustration on narrow/landscape screens.
            val artworkSize = minOf(maxWidth, maxHeight, 320.dp)
            Canvas(Modifier.fillMaxSize()) {
                val time = elapsed.value
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = (artworkSize.toPx() * .57f).coerceAtLeast(1f)
                val awakening = phase(time, 0f, 350f)
                val decoration = awakening * (1f - phase(time, 1350f, 1550f))
                drawCircle(
                    Brush.radialGradient(
                        listOf(violet.copy(alpha = .22f * decoration), Color.Transparent),
                        center = center, radius = radius,
                    ), radius, center,
                )
                // Three thin, gently expanding rings; no flashing, blur or perpetual animation.
                for (ring in 0..2) {
                    drawCircle(
                        violet.copy(alpha = decoration * (.22f - ring * .04f)),
                        radius * (.62f + ring * .16f) * (.97f + .03f * phase(time, 0f, 1350f)),
                        center, style = Stroke(1.dp.toPx()),
                    )
                }
                val accent = phase(time, 900f, 1200f) * (1f - phase(time, 1350f, 1550f))
                val halfLine = artworkSize.toPx() * .3f * accent
                val lineY = center.y + artworkSize.toPx() * .49f
                if (accent > 0f) drawLine(
                    lemon.copy(alpha = accent * .7f),
                    Offset(center.x - halfLine, lineY), Offset(center.x + halfLine, lineY),
                    strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            AnimeVaultBrandImage(
                portrait = true,
                decodeSize = 512,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(artworkSize).graphicsLayer {
                    alpha = phase(elapsed.value, 350f, 900f) * (1f - phase(elapsed.value, 900f, 1200f))
                    val scale = .965f + .035f * phase(elapsed.value, 350f, 900f)
                    scaleX = scale
                    scaleY = scale
                },
            )
            // The approved complete logo crossfades in; lettering is never re-created with a font.
            AnimeVaultBrandImage(
                decodeSize = 768,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).size(artworkSize).graphicsLayer {
                    alpha = phase(elapsed.value, 900f, 1200f)
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
