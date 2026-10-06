package com.sergey.animevault.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.theme.VaultLemonHighlight
import com.sergey.animevault.ui.theme.VaultViolet
import com.sergey.animevault.ui.theme.VaultWhite
import kotlin.math.min

/** Vault Reveal: seam → open vault → V → A → brief lemon edge → home. */
@Composable
fun AnimeVaultLaunchIntro(motionScale: Float, onFinished: () -> Unit) {
    val finished = rememberUpdatedState(onFinished)
    if (motionScale <= 0f) {
        LaunchedEffect(Unit) { finished.value() }
        return
    }
    val reduced = motionScale < 1f
    val reveal = remember { Animatable(if (reduced) .86f else 0f) }
    LaunchedEffect(motionScale) {
        reveal.animateTo(1f, tween(if (reduced) 220 else VaultMotion.splash, easing = LinearEasing))
        finished.value()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - phase(reveal.value, .87f, 1f) }
        .background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onTap = { finished.value() }) },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val p = reveal.value
            val center = Offset(size.width / 2f, size.height / 2f - 24.dp.toPx())
            val door = phase(p, .13f, .36f)
            val seam = phase(p, .02f, .12f) * (1f - phase(p, .52f, .7f))
            val glow = phase(p, .22f, .48f) * (1f - phase(p, .78f, .92f))
            val halfHeight = min(size.height * .2f, 150.dp.toPx())
            val opening = door * 52.dp.toPx()
            if (!reduced) {
                drawCircle(Brush.radialGradient(listOf(VaultViolet.copy(alpha = glow * .3f), Color.Transparent),
                    center = center, radius = 150.dp.toPx()), radius = 150.dp.toPx(), center = center)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, VaultViolet.copy(alpha = glow * .14f), Color.Transparent)),
                    topLeft = Offset(center.x - opening, center.y - halfHeight), size = Size(opening * 2f, halfHeight * 2f))
                for (side in listOf(-1, 1)) drawLine(VaultViolet.copy(alpha = seam * .9f),
                    Offset(center.x + side * opening, center.y - halfHeight * seam),
                    Offset(center.x + side * opening, center.y + halfHeight * seam),
                    strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
            val w = 130.dp.toPx()
            val h = 130.dp.toPx()
            fun point(x: Float, y: Float) = center + Offset((x - .5f) * w, (y - .5f) * h)
            fun line(a: Offset, b: Offset, amount: Float, color: Color) {
                if (amount > 0f) drawLine(color, a, a + (b - a) * amount.coerceIn(0f, 1f), 4.dp.toPx(), StrokeCap.Round)
            }
            val v = if (reduced) 1f else phase(p, .34f, .6f)
            val a = if (reduced) 1f else phase(p, .57f, .78f)
            line(point(.18f, .28f), point(.39f, .73f), v * 2f, VaultViolet)
            line(point(.39f, .73f), point(.61f, .28f), v * 2f - 1f, VaultViolet)
            line(point(.43f, .73f), point(.65f, .28f), a * 3f, VaultWhite)
            line(point(.65f, .28f), point(.84f, .73f), a * 3f - 1f, VaultWhite)
            line(point(.53f, .56f), point(.77f, .56f), a * 3f - 2f, VaultWhite)
            val edge = if (reduced) 0f else phase(p, .75f, .81f) * (1f - phase(p, .84f, .91f))
            line(point(.18f, .28f), point(.25f, .42f), edge, VaultLemonHighlight.copy(alpha = edge))
            line(point(.78f, .59f), point(.84f, .73f), edge, VaultLemonHighlight.copy(alpha = edge))
        }
        Text("ANIMEVAULT", color = VaultWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            letterSpacing = 3.sp, modifier = Modifier.graphicsLayer {
                alpha = if (reduced) 1f else phase(reveal.value, .68f, .82f)
                translationY = 74.dp.toPx()
            })
    }
}

private fun phase(value: Float, start: Float, end: Float): Float = ((value - start) / (end - start)).coerceIn(0f, 1f)
