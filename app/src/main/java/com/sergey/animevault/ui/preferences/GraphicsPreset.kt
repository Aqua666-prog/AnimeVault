package com.sergey.animevault.ui.preferences

enum class GraphicsPreset(val storageId: String) {
    PERFORMANCE("performance"),
    BALANCED("balanced"),
    CINEMATIC_ULTRA("cinematic_ultra"),
    ;

    internal companion object {
        fun fromStorage(id: String?) = entries.firstOrNull { it.storageId == id } ?: BALANCED
    }
}

internal fun GraphicsPreset.effectivePreset(powerSaveMode: Boolean): GraphicsPreset =
    if (powerSaveMode) GraphicsPreset.PERFORMANCE else this

internal fun GraphicsPreset.motionMode(appearance: AppearanceSettings, systemEnabled: Boolean): VaultMotionMode =
    when {
        !systemEnabled || appearance.effectiveMotion == VaultMotionMode.OFF -> VaultMotionMode.OFF
        this == GraphicsPreset.PERFORMANCE -> VaultMotionMode.MINIMAL
        else -> appearance.effectiveMotion
    }
