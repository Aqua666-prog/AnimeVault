package com.sergey.animevault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.sergey.animevault.ui.design.raidenEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun BoxScope.VaultPosterAura(poster: Any?, seed: String, modifier: Modifier = Modifier) {
    VaultArtwork(vaultTitleArtwork(providerPoster = poster as? String), modifier.matchParentSize(), backdrop = true)
}

/** Full-width artwork hero shared by Home and title pages. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun VaultAdaptiveHero(
    poster: Any?,
    seed: String,
    title: String,
    posterContentDescription: String,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier,
    backdropSources: List<VaultArtworkSource> = vaultTitleArtwork(providerPoster = poster as? String),
    scrollState: LazyListState? = null,
    posterSources: List<VaultArtworkSource> = vaultTitleArtwork(providerPoster = poster as? String),
    cinematic: Boolean = false,
    compact: Boolean = false,
    details: @Composable () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val visuals = LocalVaultVisualSettings.current
    val parallax = visuals.cinematicEffects && visuals.motion == VaultMotionMode.FULL && scrollState != null
    val haze = if (visuals.blurEnabled && backdropSources.isNotEmpty() && !LocalInspectionMode.current) {
        rememberHazeState()
    } else null
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).raidenEdge()) {
        val expanded = maxWidth >= 600.dp
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        val backdropHeight = if (compact) { if (expanded) 260.dp else 220.dp } else if (cinematic) { if (expanded) 440.dp else 340.dp } else if (expanded) 360.dp else 290.dp
        val artworkModifier = Modifier.fillMaxWidth().height(backdropHeight)
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
        VaultArtwork(
            backdropSources,
            if (parallax && scrollState != null) artworkModifier.graphicsLayer {
                val scroll = if (scrollState.firstVisibleItemIndex == 0) {
                    scrollState.firstVisibleItemScrollOffset.toFloat()
                } else 1000f
                alpha = 1f - (scroll / 700f).coerceIn(0f, .55f)
                translationY = -scroll.coerceAtMost(300f) * .06f
            } else artworkModifier,
            backdrop = true,
        )
        // Keep title/actions readable even over bright artwork: a tonal reading plane.
        val plane = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxWidth().height(backdropHeight).background(
            Brush.verticalGradient(listOf(plane.copy(alpha = .20f), plane.copy(alpha = .88f), plane))))
        Column(Modifier.fillMaxWidth().padding(top = if (compact) 24.dp else if (cinematic) { if (expanded) 176.dp else 104.dp } else if (expanded) 144.dp else 80.dp)) {
            val posterContent: @Composable () -> Unit = {
                VaultArtwork(posterSources,
                    posterModifier.width(if (compact) { if (expanded) 120.dp else 88.dp } else if (cinematic) { if (expanded) 184.dp else 128.dp } else if (expanded) 144.dp else 104.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(VaultRadius.small)),
                    contentDescription = posterContentDescription.takeIf { it.isNotBlank() })
            }
            val titleContent: @Composable () -> Unit = {
                val shape = MaterialTheme.shapes.large
                val panel = Modifier.clip(shape)
                    .border(VaultSize.hairline, colors.outlineVariant.copy(alpha = .55f), shape)
                val readingSurface = if (haze == null) {
                    panel.background(colors.surface.copy(alpha = .94f))
                } else {
                    panel.hazeEffect(haze) {
                        backgroundColor = colors.surface
                        tints = listOf(HazeTint(colors.surface.copy(alpha = .84f)))
                        fallbackTint = HazeTint(colors.surface.copy(alpha = .94f))
                        blurRadius = if (visuals.cinematicEffects) 20.dp else 10.dp
                        inputScale = HazeInputScale.Fixed(if (visuals.cinematicEffects) .67f else .5f)
                        noiseFactor = 0f
                    }
                }
                Column(
                    modifier = readingSurface.padding(VaultSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm),
                ) {
                    Text(title, modifier = Modifier.semantics { heading() },
                        style = (if (expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall).copy(lineBreak = LineBreak.Heading),
                        fontWeight = FontWeight.Bold)
                    details()
                }
            }
            // Content determines height: long titles and large fonts never overlap actions.
            if (stacked) {
                Column(Modifier.fillMaxWidth().padding(horizontal = VaultSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(VaultSpacing.lg)) {
                    posterContent()
                    titleContent()
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = VaultSpacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(VaultSpacing.lg)) {
                    posterContent()
                    Box(Modifier.weight(1f)) { titleContent() }
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { actions() }
            Spacer(Modifier.height(8.dp))
        }
    }
}
