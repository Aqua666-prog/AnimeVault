package com.sergey.animevault.ui.components

import androidx.compose.foundation.background
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
import com.sergey.animevault.ui.design.electroGlow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultRadius
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings

@Composable
fun BoxScope.VaultPosterAura(poster: Any?, seed: String, modifier: Modifier = Modifier) {
    VaultArtwork(vaultTitleArtwork(providerPoster = poster as? String), modifier.matchParentSize(), backdrop = true)
}

/** Full-width artwork hero shared by offline and online title pages. */
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
    val fullMotion = LocalVaultVisualSettings.current.motion == VaultMotionMode.FULL
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= 600.dp
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        val backdropHeight = if (compact) 200.dp else if (expanded) 320.dp else 260.dp
        VaultArtwork(backdropSources,
            Modifier.fillMaxWidth().height(backdropHeight), backdrop = true)
        // Keep title/actions readable even over bright artwork: a tonal reading plane.
        val plane = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxWidth().height(backdropHeight).background(
            Brush.verticalGradient(listOf(plane.copy(alpha = .20f), plane.copy(alpha = .88f), plane))))
        Column(Modifier.fillMaxWidth().padding(top = if (compact) 24.dp else if (expanded) 80.dp else 48.dp)) {
            val posterContent: @Composable () -> Unit = {
                VaultArtwork(posterSources,
                    posterModifier.width(if (compact) { if (expanded) 120.dp else 88.dp } else if (cinematic) { if (expanded) 184.dp else 128.dp } else if (expanded) 144.dp else 104.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(VaultRadius.small)),
                    contentDescription = posterContentDescription.takeIf { it.isNotBlank() })
            }
            val titleContent: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(VaultSpacing.sm)) {
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
