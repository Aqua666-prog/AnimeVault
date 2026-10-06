package com.sergey.animevault.ui.player.audio

import kotlin.math.ln
import kotlin.math.pow

internal enum class DspFilterType {
    PEAK,
    LOW_SHELF,
    HIGH_SHELF,
}

internal data class DspEqBand(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float = 1.0f,
    val type: DspFilterType = DspFilterType.PEAK,
)

internal data class DspLimiterSettings(
    val ceilingDb: Float = -1.0f,
    val lookAheadMs: Float = 5.0f,
    val releaseMs: Float = 90.0f,
    val oversampling: Int = 4,
)

internal data class DspConfig(
    val enabled: Boolean = false,
    val inputGainDb: Float = 0f,
    val autoHeadroom: Boolean = true,
    val loudnessPercent: Float = 0f,
    val bassPercent: Float = 0f,
    val eqBands: List<DspEqBand> = flatEqBands(),
    val dynamicsAmount: Float = 0f,
    val limiter: DspLimiterSettings = DspLimiterSettings(),
) {
    fun normalized(): DspConfig = copy(
        inputGainDb = inputGainDb.coerceIn(-12f, 6f),
        loudnessPercent = loudnessPercent.coerceIn(0f, 100f),
        bassPercent = bassPercent.coerceIn(0f, 100f),
        dynamicsAmount = dynamicsAmount.coerceIn(0f, 1f),
        eqBands = eqBands.take(16).map { band ->
            band.copy(
                frequencyHz = band.frequencyHz.coerceIn(20f, 20_000f),
                gainDb = band.gainDb.coerceIn(-12f, 12f),
                q = band.q.coerceIn(0.3f, 10f),
            )
        },
        limiter = limiter.copy(
            ceilingDb = limiter.ceilingDb.coerceIn(-6f, -0.1f),
            lookAheadMs = limiter.lookAheadMs.coerceIn(1f, 12f),
            releaseMs = limiter.releaseMs.coerceIn(30f, 300f),
            oversampling = when {
                limiter.oversampling >= 4 -> 4
                limiter.oversampling >= 2 -> 2
                else -> 1
            },
        ),
    )
}

internal data class DspMeters(
    val inputPeakDb: Float = -120f,
    val outputPeakDb: Float = -120f,
    val rmsDb: Float = -120f,
    val limiterGainReductionDb: Float = 0f,
    val hardClipCount: Long = 0L,
)

internal object DspPresets {
    fun config(name: String): DspConfig {
        val base = DspConfig(enabled = name != "OFF")
        return when (name) {
            "OFF" -> base.copy(enabled = false)
            "FLAT" -> base.copy(enabled = true)
            "DIALOGUE" -> base.copy(
                enabled = true,
                loudnessPercent = 28f,
                dynamicsAmount = 0.32f,
                bassPercent = 5f,
                eqBands = bands(-1f, -2.5f, -1.5f, -0.5f, 0.5f, 1.5f, 3f, 2f, 0.5f, 0f),
            )
            "BASS" -> base.copy(
                enabled = true,
                loudnessPercent = 20f,
                dynamicsAmount = 0.30f,
                bassPercent = 70f,
                eqBands = bands(1.5f, 3f, 3f, 1.5f, 0f, 0f, 0f, 0.5f, 0.5f, 0f),
            )
            "BRIGHT" -> base.copy(
                enabled = true,
                loudnessPercent = 18f,
                dynamicsAmount = 0.20f,
                eqBands = bands(-1f, -1f, -0.5f, 0f, 0.5f, 1f, 2f, 2.5f, 2f, 1f),
            )
            "NIGHT" -> base.copy(
                enabled = true,
                loudnessPercent = 48f,
                dynamicsAmount = 0.62f,
                bassPercent = 6f,
                eqBands = bands(-3.5f, -3f, -1.5f, 0f, 0.8f, 1.8f, 2.8f, 1.8f, 0.3f, -0.5f),
            )
            "CINEMA" -> base.copy(
                enabled = true,
                loudnessPercent = 25f,
                dynamicsAmount = 0.25f,
                bassPercent = 30f,
                eqBands = bands(0.5f, 1.5f, 1.5f, 0.5f, -0.5f, 0f, 0.5f, 1.2f, 1.5f, 0.5f),
            )
            "LOUD" -> base.copy(
                enabled = true,
                loudnessPercent = 72f,
                dynamicsAmount = 0.72f,
                bassPercent = 20f,
                eqBands = bands(0f, 0.8f, 1f, 0.3f, -0.8f, 0f, 1f, 1.3f, 0.7f, 0f),
                limiter = DspLimiterSettings(ceilingDb = -1.0f, lookAheadMs = 5f, releaseMs = 100f, oversampling = 4),
            )
            "MAX" -> base.copy(
                enabled = true,
                loudnessPercent = 100f,
                dynamicsAmount = 0.94f,
                bassPercent = 16f,
                eqBands = bands(-0.5f, 0.3f, 0.6f, 0f, -1f, 0f, 1.2f, 1.1f, 0.3f, -0.5f),
                limiter = DspLimiterSettings(ceilingDb = -1.0f, lookAheadMs = 6f, releaseMs = 120f, oversampling = 4),
            )
            else -> base.copy(enabled = true)
        }.normalized()
    }

    private fun bands(vararg gains: Float): List<DspEqBand> = STANDARD_FREQUENCIES.mapIndexed { index, hz ->
        DspEqBand(frequencyHz = hz, gainDb = gains.getOrElse(index) { 0f }, q = 1.0f)
    }
}

internal val STANDARD_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

internal fun flatEqBands(): List<DspEqBand> = STANDARD_FREQUENCIES.map { DspEqBand(it, 0f) }

internal fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

internal fun linearToDb(value: Float): Float = if (value <= 1e-9f || !value.isFinite()) {
    -120f
} else {
    (20.0 * ln(value.toDouble()) / ln(10.0)).toFloat().coerceAtLeast(-120f)
}
