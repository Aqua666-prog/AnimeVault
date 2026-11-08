package com.sergey.animevault.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.theme.LocalRaiden
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings

/** Small cached ornament; no shaders, bitmap layers or endless frame clock. */
fun Modifier.raidenEdge(): Modifier = composed {
    val t = LocalRaiden.current
    if (!t.enabled) this else drawWithCache {
        val wash = Brush.linearGradient(listOf(t.gold.copy(alpha = .05f), t.glow.copy(alpha = .07f), Color.Transparent))
        onDrawWithContent {
            drawRect(wash)
            drawContent()
            val x = 12.dp.toPx(); val length = 22.dp.toPx().coerceAtMost(size.width / 5)
            drawLine(t.gold.copy(alpha = .35f), Offset(x, x), Offset(x + length, x), 1.dp.toPx())
            drawLine(t.glow.copy(alpha = .3f), Offset(size.width-x-length, size.height-x), Offset(size.width-x, size.height-x), 1.dp.toPx())
        }
    }
}

/** One soft pulse on a selection change. Reduced/off modes are entirely static. */
fun Modifier.electroGlow(selected: Boolean): Modifier = composed {
    val t = LocalRaiden.current
    val allowed = LocalVaultVisualSettings.current.decorativeAnimations
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(selected, allowed, t.dynamic) {
        pulse.snapTo(0f)
        if (selected && allowed && t.dynamic) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(700))
        }
    }
    if (!t.enabled) this else drawWithCache {
        onDrawWithContent {
            if (selected) drawRect(t.glow.copy(alpha = .08f + pulse.value * .10f))
            drawContent()
        }
    }
}

/** Original threefold electro ornament, not a reproduced game logo. */
@Composable
fun RaidenEmblem(modifier: Modifier = Modifier) {
    val t = LocalRaiden.current
    Canvas(modifier) {
        val radius = size.minDimension * .35f
        drawCircle(t.gold.copy(alpha = .5f), radius * 1.15f, style = Stroke(1.dp.toPx()))
        for (index in 0..2) rotate(index * 120f) {
            val path = Path().apply {
                moveTo(center.x, center.y-radius)
                cubicTo(center.x+radius, center.y-radius*.6f, center.x+radius*.7f, center.y+radius*.4f, center.x+radius*.15f, center.y)
                quadraticTo(center.x+radius*.5f, center.y-radius*.2f, center.x, center.y-radius)
                close()
            }
            drawPath(path, t.glow.copy(alpha = .8f))
        }
        drawLine(t.gold, center-Offset(-radius*.15f,radius*.4f), center+Offset(-radius*.15f,radius*.4f), 2.dp.toPx())
    }
}
