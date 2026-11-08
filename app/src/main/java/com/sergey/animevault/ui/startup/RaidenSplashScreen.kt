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
import com.sergey.animevault.ui.design.RaidenEmblem
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
    val time = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) finish() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(1600, easing = LinearEasing)); finish() }
    BackHandler { finish() }
    RaidenSplashArtwork(time.value, Modifier.clickable(role = Role.Button, onClickLabel = "Пропустить заставку", onClick = { finish() }))
}

@Composable
internal fun RaidenSplashArtwork(progress: Float, modifier: Modifier = Modifier) {
    val t = LocalRaiden.current
    fun phase(start: Float, end: Float): Float {
        val x = ((progress-start)/(end-start)).coerceIn(0f, 1f)
        return x*x*(3-2*x)
    }
    val arrival = phase(0f, .3f)
    val title = phase(.3f, .6f)
    Box(modifier.fillMaxSize().graphicsLayer { alpha = 1-phase(.82f, 1f) }
        .background(Brush.verticalGradient(listOf(t.dusk, t.background)))) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension.coerceAtLeast(1f)*.8f
            drawCircle(Brush.radialGradient(listOf(t.glow.copy(alpha = .16f*arrival*(1-phase(.4f,.8f))), Color.Transparent),
                center = center, radius = radius), radius, center)
            if (t.dynamic) for (i in 0 until 7) {
                val x = size.width*(i+1)/8 + sin(i*2.4f+progress)*16.dp.toPx()
                val y = size.height*(.1f+(i%3)*.23f+progress*.12f)
                drawOval(t.sakura.copy(alpha = .3f*arrival), Offset(x,y), Size(5.dp.toPx(),10.dp.toPx()))
            }
            val width = size.width*.23f*title
            drawLine(t.gold.copy(alpha = .6f*title), Offset(center.x-width,size.height*.68f),Offset(center.x+width,size.height*.68f),1.dp.toPx())
        }
        Column(Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaidenEmblem(Modifier.size(108.dp).graphicsLayer { alpha = arrival })
            Text("AnimeVault", style = MaterialTheme.typography.displaySmall, color = t.text,
                modifier = Modifier.graphicsLayer { alpha = title; translationY = (1-title)*8.dp.toPx() })
            Text(if (t.dynamic) "Мусо но Хитотати" else "Обитель Эвтюмии", color = t.gold,
                modifier = Modifier.graphicsLayer { alpha = title })
        }
        Text("Пропустить заставку", color = t.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp))
    }
}
