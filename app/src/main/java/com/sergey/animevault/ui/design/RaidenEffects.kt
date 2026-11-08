package com.sergey.animevault.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.StrokeCap
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
            pulse.animateTo(0f, tween(RaidenMotion.selectionMillis))
        }
    }
    if (!t.enabled) this else drawWithCache {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.maxDimension.coerceAtLeast(1f)
        val light = Brush.radialGradient(listOf(t.glow.copy(alpha = .18f), Color.Transparent), center, radius)
        onDrawWithContent {
            if (selected) {
                drawRect(light, alpha = .45f + pulse.value * .55f)
            }
            drawContent()
            if (selected) {
                if (t.dynamic && pulse.value > 0f) {
                    val y=size.height-5.dp.toPx()
                    val bolt=Path().apply {
                        moveTo(size.width*.15f,y); lineTo(size.width*.43f,y)
                        lineTo(size.width*.49f,y-3.dp.toPx()); lineTo(size.width*.52f,y+2.dp.toPx())
                        lineTo(size.width*.57f,y); lineTo(size.width*.85f,y)
                    }
                    drawPath(bolt,t.glow.copy(alpha=.28f*pulse.value),style=Stroke(1.dp.toPx()))
                }
                val half = size.width * .18f
                drawLine(t.gold.copy(alpha = .65f), Offset(center.x-half, size.height-2.dp.toPx()),
                    Offset(center.x+half, size.height-2.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** Original threefold electro ornament, not a reproduced game logo. */
@Composable
fun RaidenEmblem(modifier: Modifier = Modifier) {
    val t = LocalRaiden.current
    Box(modifier.drawWithCache {
        // CacheDrawScope exposes size, while DrawScope.center exists only during drawing.
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .35f
        val petal = Path().apply {
            moveTo(center.x, center.y-radius)
            cubicTo(center.x+radius, center.y-radius*.6f, center.x+radius*.7f, center.y+radius*.4f, center.x+radius*.15f, center.y)
            quadraticTo(center.x+radius*.5f, center.y-radius*.2f, center.x, center.y-radius)
            close()
        }
        val bolt = Path().apply {
            moveTo(center.x+radius*.15f, center.y-radius*.42f)
            lineTo(center.x-radius*.2f, center.y+radius*.04f)
            lineTo(center.x+radius*.1f, center.y+radius*.04f)
            lineTo(center.x-radius*.15f, center.y+radius*.42f)
        }
        val aura = Brush.radialGradient(listOf(t.glow.copy(alpha=.13f), Color.Transparent), center, radius*1.4f)
        onDrawBehind {
            drawCircle(aura, radius*1.4f)
            drawCircle(t.gold.copy(alpha=.4f), radius*1.15f, style=Stroke(1.dp.toPx()))
            drawCircle(t.glow.copy(alpha=.18f), radius*1.32f, style=Stroke(.5.dp.toPx()))
            for (index in 0..2) rotate(index*120f) { drawPath(petal, t.glow.copy(alpha=.8f)) }
            drawPath(bolt, t.gold, style=Stroke(1.5.dp.toPx(), cap=StrokeCap.Round))
        }
    })
}

/** Original roofline and pillars, bounded to a small static area behind screen chrome. */
@Composable
fun RaidenHorizon(modifier: Modifier = Modifier) {
    val t = LocalRaiden.current
    if (!t.enabled) return
    Box(modifier.fillMaxWidth().height(180.dp).drawWithCache {
        val roof = Path().apply {
            moveTo(0f, size.height*.72f)
            quadraticTo(size.width*.22f, size.height*.74f, size.width*.5f, size.height*.32f)
            quadraticTo(size.width*.78f, size.height*.74f, size.width, size.height*.72f)
        }
        val wash = Brush.radialGradient(listOf(t.glow.copy(alpha=.10f), Color.Transparent),
            Offset(size.width*.8f, 0f), size.width.coerceAtLeast(1f))
        // Torii silhouette and sixfold geometric stars are original, fixed vector geometry.
        val gate = Path().apply {
            val left=size.width*.72f; val right=size.width*.94f
            val top=size.height*.30f; val foot=size.height*.93f
            moveTo(left-8.dp.toPx(),top); quadraticTo((left+right)/2,top+6.dp.toPx(),right+8.dp.toPx(),top)
            moveTo(left,top+12.dp.toPx()); lineTo(right,top+12.dp.toPx())
            moveTo(left+8.dp.toPx(),top); lineTo(left+8.dp.toPx(),foot)
            moveTo(right-8.dp.toPx(),top); lineTo(right-8.dp.toPx(),foot)
        }
        val star = Path().apply {
            val origin=Offset(size.width*.12f,size.height*.40f)
            val radius=18.dp.toPx()
            for (index in 0 until 6) {
                val angle=index*Math.PI/3
                val point=origin+Offset(kotlin.math.cos(angle).toFloat()*radius,kotlin.math.sin(angle).toFloat()*radius)
                val nextAngle=(index+1)*Math.PI/3
                moveTo(origin.x,origin.y); lineTo(point.x,point.y)
                lineTo(origin.x+kotlin.math.cos(nextAngle).toFloat()*radius,origin.y+kotlin.math.sin(nextAngle).toFloat()*radius)
            }
        }
        onDrawBehind {
            drawRect(wash)
            drawPath(gate,t.gold.copy(alpha=if(t.dynamic) .13f else .08f),style=Stroke(2.dp.toPx()))
            drawPath(star,t.glow.copy(alpha=.12f),style=Stroke(.75.dp.toPx()))
            drawPath(roof, t.gold.copy(alpha=.10f), style=Stroke(1.dp.toPx()))
            for (index in 1..5) {
                val x=size.width*index/6f
                drawLine(t.gold.copy(alpha=.05f), Offset(x,size.height*.78f), Offset(x,size.height))
            }
        }
    })
}

/** Decorative divider has no semantic content and never animates behind reading text. */
@Composable
fun RaidenDivider(modifier: Modifier = Modifier) {
    val t=LocalRaiden.current
    if (!t.enabled) return
    Box(modifier.fillMaxWidth().height(8.dp).drawWithCache {
        val origin=Offset(size.width/2,size.height/2)
        val diamond=Path().apply {
            moveTo(origin.x,0f); lineTo(origin.x+size.height/2,origin.y)
            lineTo(origin.x,size.height); lineTo(origin.x-size.height/2,origin.y); close()
        }
        val line=Brush.horizontalGradient(listOf(Color.Transparent,t.gold.copy(alpha=.4f),Color.Transparent))
        onDrawBehind {
            drawLine(line,Offset(0f,origin.y),Offset(size.width,origin.y),1.dp.toPx())
            drawPath(diamond,t.gold.copy(alpha=.65f))
        }
    })
}
