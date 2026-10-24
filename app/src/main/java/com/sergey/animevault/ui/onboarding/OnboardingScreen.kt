package com.sergey.animevault.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sergey.animevault.ui.components.AnimeVaultBrandImage
import com.sergey.animevault.ui.settings.AccountUiState
import kotlin.math.roundToInt

private val Lemon = Color(0xFFD9FF4A)
private val Violet = Color(0xFF9B6BFF)
private val Ink = Color(0xFF09080D)
private val White = Color(0xFFF7F4FF)
private val Muted = Color(0xFFB8B0C6)

@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    account: AccountUiState,
    motionScale: Float,
    onStart: () -> Unit,
    onGoogle: () -> Unit,
    onGuest: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("first-launch")) {
        AmbientGlow()
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            val compact = maxHeight < 720.dp || LocalDensity.current.fontScale > 1.2f
            if (state.gate == LaunchGate.LOADING || state.gate == LaunchGate.ERROR) {
                Column(
                    Modifier.align(Alignment.Center).widthIn(max = 440.dp)
                        .padding(32.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    BrandArtwork(Modifier.fillMaxWidth().height(160.dp))
                    if (state.gate == LaunchGate.ERROR) {
                        Notice(state.error.orEmpty(), true)
                        PrimaryAction("Попробовать снова", onRetry)
                    } else {
                        CircularProgressIndicator(color = Lemon, modifier = Modifier.size(24.dp))
                    }
                }
            } else if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(max = 760.dp).fillMaxSize(),
                        shape = RoundedCornerShape(36.dp),
                        color = Color(0xFF100C19),
                        border = BorderStroke(1.dp, Violet.copy(alpha = .2f)),
                    ) {
                        Column(
                            Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BrandArtwork(Modifier.fillMaxWidth().height(if (compact) 180.dp else 320.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Твоё аниме. Твоя коллекция. Твой мир.", color = Muted,
                                fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
                        }
                    }
                    Column(
                        Modifier.weight(1f).widthIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    ) {
                        StepHeader(state.step, onBack)
                        Spacer(Modifier.height(28.dp))
                        Page(state, account, motionScale, compact, false,
                            onStart, onGoogle, onGuest, onFinish)
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp)
                        .fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    StepHeader(state.step, onBack)
                    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                    Page(state, account, motionScale, compact, true,
                        onStart, onGoogle, onGuest, onFinish)
                }
            }
        }
    }
}

@Composable
private fun Page(
    state: OnboardingUiState,
    account: AccountUiState,
    motionScale: Float,
    compact: Boolean,
    showBrand: Boolean,
    onStart: () -> Unit,
    onGoogle: () -> Unit,
    onGuest: () -> Unit,
    onFinish: () -> Unit,
) {
    val duration = (340 * motionScale).roundToInt().coerceAtLeast(0)
    var appeared by remember { mutableStateOf(duration == 0) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(if (appeared) 1f else 0f, tween(duration), label = "welcome-entrance")
    AnimatedContent(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        targetState = state.step,
        transitionSpec = {
            val enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) {
                if (motionScale >= 1f) it / 24 else 0
            }
            (enter togetherWith fadeOut(tween(duration / 2))).using(
                SizeTransform(clip = false) { _, _ -> tween(duration) },
            )
        },
        label = "first-launch-page",
    ) { step ->
        Column(
            Modifier.fillMaxWidth().testTag("onboarding-" + step.name.lowercase()),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    if (showBrand) {
                        BrandArtwork(Modifier.fillMaxWidth().height(if (compact) 136.dp else 200.dp))
                    }
                    Eyebrow("МЕСТО ДЛЯ ТВОИХ ИСТОРИЙ")
                    Headline("Добро пожаловать в AnimeVault", if (compact) 28 else 32)
                    Text("Твоё аниме. Твоя коллекция. Твой мир.", color = Muted,
                        fontSize = 17.sp, lineHeight = 25.sp)
                    Features()
                    PrimaryAction("Начать", onStart)
                    Footnote("Смотри по-своему")
                }
                OnboardingStep.ACCOUNT -> {
                    if (showBrand) {
                        BrandArtwork(Modifier.fillMaxWidth().height(if (compact) 136.dp else 180.dp))
                    }
                    Eyebrow("ОДИН ШАГ ДО НОВОЙ ИСТОРИИ")
                    Headline("Твой мир аниме начинается здесь", if (compact) 30 else 36)
                    Text("Войди с Google или начни без аккаунта. Все возможности AnimeVault доступны и гостям.",
                        color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryAction(
                            label = if (account.busy) "Подключаемся…" else "Продолжить с Google",
                            onClick = onGoogle,
                            enabled = !account.busy,
                            icon = Icons.Outlined.AccountCircle,
                            busy = account.busy,
                        )
                        OutlinedButton(
                            onClick = onGuest,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("continue-guest"),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, White.copy(alpha = .22f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        ) {
                            Text("Продолжить как гость", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center)
                        }
                    }
                    account.message?.let { Notice(it, account.isError) }
                    Footnote("Войти через Google можно позже в настройках")
                }
                OnboardingStep.FINISH -> {
                    if (showBrand) {
                        BrandArtwork(Modifier.fillMaxWidth().height(if (compact) 136.dp else 200.dp))
                    }
                    Surface(shape = CircleShape, color = Lemon.copy(alpha = .12f)) {
                        Icon(Icons.Outlined.Check, contentDescription = "Всё готово",
                            tint = Lemon, modifier = Modifier.padding(18.dp).size(36.dp))
                    }
                    Eyebrow("НОВАЯ ИСТОРИЯ НАЧИНАЕТСЯ")
                    Headline(state.name?.let { "Привет, $it!" } ?: "Добро пожаловать!", 36)
                    Text(
                        if (state.name == null) "Твоя следующая любимая история уже ждёт. Начнём?"
                        else "Рады видеть тебя в AnimeVault. Выбирай историю под своё настроение.",
                        color = Muted, fontSize = 17.sp, lineHeight = 26.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    PrimaryAction(
                        if (state.error == null) "В AnimeVault" else "Попробовать снова",
                        onFinish, enabled = !state.saving, busy = state.saving,
                    )
                    state.error?.let { Notice(it, true) }
                    Footnote("Каталог · коллекции · просмотр · офлайн")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepHeader(step: OnboardingStep, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (step != OnboardingStep.WELCOME) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("onboarding-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = White)
            }
        } else {
            Text("ANIMEVAULT", color = White.copy(alpha = .7f), fontSize = 11.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                Box(Modifier.width(if (index == step.ordinal) 24.dp else 7.dp)
                    .height(5.dp).background(if (index <= step.ordinal) Lemon else White.copy(alpha = .16f),
                        CircleShape))
            }
            Spacer(Modifier.width(6.dp))
            Text("0${step.ordinal + 1} / 03", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Headline(text: String, size: Int) {
    Text(text, color = White, fontSize = size.sp, lineHeight = (size + 5).sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, color = Violet, fontSize = 10.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
}

@Composable
private fun Footnote(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Features() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Feature(Icons.Outlined.GridView, "Каталог", "Найди своё", Modifier.weight(1f))
            Feature(Icons.Outlined.Bookmarks, "Коллекции", "Собери любимое", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Feature(Icons.Outlined.PlayArrow, "Просмотр", "В твоём ритме", Modifier.weight(1f))
            Feature(Icons.Outlined.Download, "Офлайн", "Смотри без сети", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, detail: String, modifier: Modifier) {
    Surface(modifier = modifier, color = Color(0xFF15111E), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = .13f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Lemon, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("primary-action"),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lemon, contentColor = Ink,
            disabledContainerColor = Lemon.copy(alpha = .65f), disabledContentColor = Ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Ink, strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Notice(text: String, error: Boolean) {
    val color = if (error) Color(0xFFFFB3CC) else Violet
    Surface(color = color.copy(alpha = .09f), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = .25f))) {
        Row(Modifier.padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(text, color = White, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** The complete approved logo includes the lettering; no separate imitation wordmark. */
@Composable
private fun BrandArtwork(modifier: Modifier) {
    AnimeVaultBrandImage(modifier = modifier)
}

@Composable
private fun AmbientGlow() {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.width * 1.05f
        drawCircle(Brush.radialGradient(
            listOf(Violet.copy(alpha = .1f), Color.Transparent),
            center = Offset(size.width, size.height * .12f), radius = radius,
        ), radius, Offset(size.width, size.height * .12f))
        drawCircle(Brush.radialGradient(
            listOf(Lemon.copy(alpha = .035f), Color.Transparent),
            center = Offset(0f, size.height * .9f), radius = radius,
        ), radius, Offset(0f, size.height * .9f))
    }
}
