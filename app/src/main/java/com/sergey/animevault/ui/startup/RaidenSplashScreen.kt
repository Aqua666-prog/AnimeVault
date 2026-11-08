package com.sergey.animevault.ui.startup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sergey.animevault.ui.design.RaidenMotion
import com.sergey.animevault.ui.design.RaidenHorizon
import com.sergey.animevault.ui.design.RaidenEmblem
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import com.sergey.animevault.ui.theme.LocalRaiden
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import kotlin.math.sin

/** Finite composition after the system splash; navigation already runs underneath. */
@Composable
fun RaidenSplashScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    if (!LocalVaultVisualSettings.current.decorativeAnimations) {
        LaunchedEffect(Unit) { finish() }
        return
    }
    val dynamic = LocalRaiden.current.dynamic
    val time = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) finish() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(if (dynamic) RaidenMotion.splashMillis else 1800, easing = LinearEasing)); finish() }
    BackHandler { finish() }
    RaidenSplashArtwork(time.value, Modifier.clickable(role = Role.Button, onClickLabel = "Пропустить заставку", onClick = { finish() }))
}

@Composable
internal fun RaidenSplashArtwork(progress: Float, modifier: Modifier = Modifier) {
    val t = LocalRaiden.current
    val arrival = RaidenMotion.arrival(progress)
    val title = RaidenMotion.title(progress)
    val wave = RaidenMotion.wave(progress)
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { alpha = RaidenMotion.exit(progress) }
        .background(Brush.verticalGradient(listOf(t.dusk, t.background)))) {
        RaidenHorizon(Modifier.align(Alignment.TopCenter))
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.minDimension.coerceAtLeast(1f)
            val radius = unit*.8f
            drawCircle(Brush.radialGradient(listOf(t.glow.copy(alpha=.13f*arrival), Color.Transparent),
                center=center, radius=radius), radius, center)
            // One expanding soft ring. Opacity stays low throughout, with no flashes.
            val ringAlpha = (if (t.dynamic) .16f else .07f) * sin(wave * Math.PI).toFloat().coerceAtLeast(0f)
            drawCircle(t.glow.copy(alpha=ringAlpha), unit*(.12f+wave*.75f), center,
                style=Stroke((unit*.025f).coerceAtLeast(1f)))
            if (t.dynamic) {
                val petal = Path().apply {
                    moveTo(0f,-6.dp.toPx())
                    cubicTo(7.dp.toPx(),-3.dp.toPx(),5.dp.toPx(),5.dp.toPx(),0f,7.dp.toPx())
                    cubicTo(-5.dp.toPx(),3.dp.toPx(),-4.dp.toPx(),-2.dp.toPx(),0f,-6.dp.toPx())
                }
                for (i in 0 until 7) {
                    val x=size.width*(i+1)/8 + sin(i*2.4f+wave*2f)*18.dp.toPx()
                    val y=size.height*(.1f+(i%3)*.22f+wave*.14f)
                    withTransform({
                        translate(x,y); rotate(i*47f+wave*55f, Offset.Zero)
                    }) { drawPath(petal, t.sakura.copy(alpha=.28f*arrival)) }
                }
            }
        }
        val emblemSize = if (maxHeight < 480.dp) 64.dp else 112.dp
        Column(Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(12.dp)) {
            RaidenEmblem(Modifier.size(emblemSize).graphicsLayer {
                alpha=arrival; scaleX=.92f+.08f*arrival; scaleY=scaleX
            })
            Text("AnimeVault", style=MaterialTheme.typography.displaySmall, color=t.text, textAlign=TextAlign.Center,
                modifier=Modifier.graphicsLayer { alpha=title; translationY=(1-title)*8.dp.toPx() })
            Box(Modifier.width(156.dp).height(1.dp).graphicsLayer { alpha=title; scaleX=title }
                .background(Brush.horizontalGradient(listOf(Color.Transparent,t.gold,Color.Transparent))))
            Text(if (t.dynamic) "Мусо но Хитотати" else "Обитель Эвтюмии", color=t.gold, textAlign=TextAlign.Center,
                style=MaterialTheme.typography.labelLarge, modifier=Modifier.graphicsLayer { alpha=title })
        }
        Text("Пропустить заставку", color = t.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp))
    }
}
