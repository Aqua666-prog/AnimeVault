package com.sergey.animevault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import com.sergey.animevault.ui.design.VaultRadius

val AnimeVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(VaultRadius.micro),
    small = RoundedCornerShape(VaultRadius.small),
    medium = RoundedCornerShape(VaultRadius.medium),
    large = RoundedCornerShape(VaultRadius.large),
    extraLarge = RoundedCornerShape(VaultRadius.extraLarge),
)
