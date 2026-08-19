package com.sergey.animevault.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sergey.animevault.ui.design.VaultAlpha
import com.sergey.animevault.ui.design.VaultInteractivePanel
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultRadius
import com.sergey.animevault.ui.design.VaultSize
import com.sergey.animevault.ui.design.VaultSpacing
import com.sergey.animevault.ui.design.VaultSurfaceRole
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import com.sergey.animevault.ui.theme.vaultMotionDuration

/** Shared pieces that define AnimeVault's visual language. */
@Composable
fun VaultScreenHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VaultSpacing.xxs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Search is treated as a quiet glass command surface, not a stock Material field. */
@Composable
fun VaultSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { (onClear ?: { onValueChange("") })() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Очистить")
                }
            }
        } else null,
        shape = RoundedCornerShape(VaultRadius.large),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = VaultAlpha.glass),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.54f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
fun VaultSectionHeader(
    title: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VaultSpacing.lg, vertical = VaultSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = VaultSpacing.xs)
                .size(width = 3.dp, height = 30.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                        ),
                    ),
                    RoundedCornerShape(50),
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            supporting?.let {
                Spacer(Modifier.height(VaultSpacing.xxs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun VaultGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    VaultPanel(
        modifier = modifier,
        role = VaultSurfaceRole.Glass,
        shape = RoundedCornerShape(VaultRadius.large),
    ) { content() }
}

/** Squircle action replaces the generic floating circles from the old chrome. */

@Composable
fun VaultActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    VaultInteractivePanel(
        modifier = modifier,
        onClick = onClick,
        role = VaultSurfaceRole.Card,
        shape = RoundedCornerShape(VaultRadius.large),
        accent = accent,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.055f), Color.Transparent),
                ),
            ),
        ) {
            content()
        }
    }
}

@Composable
fun VaultTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    VaultInteractivePanel(
        modifier = modifier.padding(horizontal = VaultSpacing.xxs),
        onClick = onClick,
        role = VaultSurfaceRole.Elevated,
        shape = RoundedCornerShape(VaultRadius.medium),
    ) {
        Box(
            modifier = Modifier.size(VaultSize.topBarAction),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(VaultSize.icon),
                tint = tint,
            )
        }
    }
}


/** Small semantic icon tile used inside actions and dense metadata rows. */
@Composable
fun VaultIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(VaultRadius.medium),
        color = accent.copy(alpha = 0.10f),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.padding(10.dp).size(VaultSize.icon),
            tint = accent,
        )
    }
}


@Composable
fun VaultFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        shape = RoundedCornerShape(VaultRadius.medium),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.70f),
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
fun VaultStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.095f),
        border = BorderStroke(VaultSize.hairline, accent.copy(alpha = 0.24f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
    }
}

@Composable
fun VaultInfoCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    VaultPanel(
        modifier = modifier.fillMaxWidth(),
        role = VaultSurfaceRole.Card,
        shape = RoundedCornerShape(VaultRadius.large),
    ) {
        Row(
            modifier = Modifier.padding(VaultSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(VaultRadius.medium),
                color = accent.copy(alpha = 0.10f),
                border = BorderStroke(VaultSize.hairline, accent.copy(alpha = 0.22f)),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(VaultSize.icon),
                    tint = accent,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VaultSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun VaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(VaultRadius.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(VaultSize.compactIcon))
            Spacer(Modifier.size(VaultSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Generic empty/error state so screens never fall back to debug-placeholder chrome. */
@Composable
fun VaultEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    VaultPanel(
        modifier = modifier.padding(VaultSpacing.xxl),
        role = VaultSurfaceRole.Glass,
        shape = RoundedCornerShape(VaultRadius.extraLarge),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = VaultSpacing.xxl, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(VaultRadius.large),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                border = BorderStroke(
                    VaultSize.hairline,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                ),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(VaultSpacing.lg).size(VaultSize.largeIcon),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(VaultSpacing.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(VaultSpacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(VaultSpacing.xl))
                VaultPrimaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

/** Lightweight pulse placeholder. No image dependency and cheap enough for grids. */
@Composable
fun VaultSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(VaultRadius.medium),
) {
    val motion = LocalVaultVisualSettings.current.motion
    val alpha = if (motion == VaultMotionMode.MINIMAL) {
        0.11f
    } else {
        val transition = rememberInfiniteTransition(label = "vault-skeleton")
        val animated by transition.animateFloat(
            initialValue = 0.07f,
            targetValue = 0.17f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (motion == VaultMotionMode.REDUCED) VaultMotion.skeleton * 2 else VaultMotion.skeleton),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "vault-skeleton-alpha",
        )
        animated
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
    ) {}
}

/** Consistent press motion used for media cards. */
fun Modifier.vaultClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val duration = vaultMotionDuration(if (pressed) VaultMotion.pressIn else VaultMotion.pressOut)
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.982f else 1f,
        animationSpec = tween(durationMillis = duration),
        label = "vault-card-press",
    )
    this
        .scale(scale)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
}

/** Editorial heading for modal sheets and dense utility panels. */
@Composable
fun VaultSheetHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VaultSpacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(width = 4.dp, height = 34.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    ),
                    RoundedCornerShape(50),
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(VaultSpacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
