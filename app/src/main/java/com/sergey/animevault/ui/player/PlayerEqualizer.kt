package com.sergey.animevault.ui.player

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.sergey.animevault.ui.components.VaultSheetHeader
import com.sergey.animevault.ui.preferences.UiPreferences
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/** Настройки звука и скорости, изолированные для одного локального или онлайн-тайтла. */
internal class PlayerPreferences(
    context: Context,
    titleKey: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val keySuffix = titleKey.sha256Prefix()
    private val globalPreferences = UiPreferences(context)

    var speed: Float
        get() = if (preferences.contains("speed_$keySuffix")) {
            preferences.getFloat("speed_$keySuffix", 1f).coerceIn(0.5f, 2f)
        } else {
            globalPreferences.playbackDefaults().speed
        }
        set(value) = preferences.edit { putFloat("speed_$keySuffix", value.coerceIn(0.5f, 2f)) }

    var videoScaleMode: VideoScaleMode
        get() = preferences.getString("video_scale_$keySuffix", null)
            ?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
            ?: VideoScaleMode.valueOf(globalPreferences.playbackDefaults().videoScale.name)
        set(value) = preferences.edit { putString("video_scale_$keySuffix", value.name) }

    var nextEpisodeMode: NextEpisodeMode
        get() = preferences.getString("next_episode_$keySuffix", null)
            ?.let { stored -> NextEpisodeMode.entries.firstOrNull { it.name == stored } }
            ?: NextEpisodeMode.valueOf(globalPreferences.playbackDefaults().nextEpisode.name)
        set(value) = preferences.edit { putString("next_episode_$keySuffix", value.name) }

    /** Anime4K is experimental and deliberately off until explicitly enabled per title. */
    var anime4kEnabled: Boolean
        get() = preferences.getBoolean("anime4k_light_$keySuffix", false)
        set(value) = preferences.edit { putBoolean("anime4k_light_$keySuffix", value) }

    /** Last manually chosen online voice/source. Used only when a title has online streams. */
    var preferredTranslation: String?
        get() = preferences.getString("stream_translation_$keySuffix", null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit { putString("stream_translation_$keySuffix", value?.takeIf { it.isNotBlank() }) }

    var preferredSourceName: String?
        get() = preferences.getString("stream_source_$keySuffix", null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit { putString("stream_source_$keySuffix", value?.takeIf { it.isNotBlank() }) }

    var preferredQuality: Int?
        get() = preferences.getInt("stream_quality_$keySuffix", -1).takeIf { it > 0 }
        set(value) = preferences.edit {
            if (value != null && value > 0) putInt("stream_quality_$keySuffix", value)
            else remove("stream_quality_$keySuffix")
        }

    var skipSettings: PlayerSkipSettings
        get() = PlayerSkipSettings(
            autoSkipOpening = preferences.getBoolean("skip_opening_enabled_$keySuffix", false),
            openingStartMs = preferences.getLong("skip_opening_start_$keySuffix", 0L),
            openingEndMs = preferences.getLong("skip_opening_end_$keySuffix", 0L),
            autoSkipEnding = preferences.getBoolean("skip_ending_enabled_$keySuffix", false),
            endingStartMs = preferences.getLong("skip_ending_start_$keySuffix", 0L),
            endingEndMs = preferences.getLong("skip_ending_end_$keySuffix", 0L),
        ).normalized()
        set(value) {
            val safe = value.normalized()
            preferences.edit {
                putBoolean("skip_opening_enabled_$keySuffix", safe.autoSkipOpening)
                putLong("skip_opening_start_$keySuffix", safe.openingStartMs)
                putLong("skip_opening_end_$keySuffix", safe.openingEndMs)
                putBoolean("skip_ending_enabled_$keySuffix", safe.autoSkipEnding)
                putLong("skip_ending_start_$keySuffix", safe.endingStartMs)
                putLong("skip_ending_end_$keySuffix", safe.endingEndMs)
            }
        }

    fun hasManualTitleSkipSettings(): Boolean = preferences.contains("skip_opening_start_$keySuffix") ||
        preferences.contains("skip_ending_start_$keySuffix")

    fun providerSkipDisabled(scopeKey: String): Boolean =
        preferences.getBoolean("provider_skip_disabled_${scopeKey.hashCode().toString()}_$keySuffix", false)

    fun setProviderSkipDisabled(scopeKey: String, disabled: Boolean) {
        preferences.edit { putBoolean("provider_skip_disabled_${scopeKey.hashCode().toString()}_$keySuffix", disabled) }
    }

    fun scopedSkipSettings(scopeKey: String): PlayerSkipSettings? {
        val scope = scopeKey.hashCode().toString()
        if (!preferences.contains("scope_skip_opening_start_${scope}_$keySuffix") &&
            !preferences.contains("scope_skip_ending_start_${scope}_$keySuffix")) return null
        return PlayerSkipSettings(
            autoSkipOpening = preferences.getBoolean("scope_skip_opening_enabled_${scope}_$keySuffix", false),
            openingStartMs = preferences.getLong("scope_skip_opening_start_${scope}_$keySuffix", 0L),
            openingEndMs = preferences.getLong("scope_skip_opening_end_${scope}_$keySuffix", 0L),
            autoSkipEnding = preferences.getBoolean("scope_skip_ending_enabled_${scope}_$keySuffix", false),
            endingStartMs = preferences.getLong("scope_skip_ending_start_${scope}_$keySuffix", 0L),
            endingEndMs = preferences.getLong("scope_skip_ending_end_${scope}_$keySuffix", 0L),
        ).normalized()
    }

    fun setScopedSkipSettings(scopeKey: String, value: PlayerSkipSettings) {
        val scope = scopeKey.hashCode().toString()
        val safe = value.normalized()
        preferences.edit {
            putBoolean("scope_skip_opening_enabled_${scope}_$keySuffix", safe.autoSkipOpening)
            putLong("scope_skip_opening_start_${scope}_$keySuffix", safe.openingStartMs)
            putLong("scope_skip_opening_end_${scope}_$keySuffix", safe.openingEndMs)
            putBoolean("scope_skip_ending_enabled_${scope}_$keySuffix", safe.autoSkipEnding)
            putLong("scope_skip_ending_start_${scope}_$keySuffix", safe.endingStartMs)
            putLong("scope_skip_ending_end_${scope}_$keySuffix", safe.endingEndMs)
        }
    }

    fun clearScopedSkipSettings(scopeKey: String) {
        val scope = scopeKey.hashCode().toString()
        preferences.edit {
            remove("scope_skip_opening_enabled_${scope}_$keySuffix")
            remove("scope_skip_opening_start_${scope}_$keySuffix")
            remove("scope_skip_opening_end_${scope}_$keySuffix")
            remove("scope_skip_ending_enabled_${scope}_$keySuffix")
            remove("scope_skip_ending_start_${scope}_$keySuffix")
            remove("scope_skip_ending_end_${scope}_$keySuffix")
        }
    }

    var equalizerPreset: EqualizerPreset
        get() = preferences.getString("eq_preset_$keySuffix", null)
            ?.let { stored -> EqualizerPreset.entries.firstOrNull { it.name == stored } }
            ?: EqualizerPreset.valueOf(globalPreferences.playbackDefaults().equalizer.name)
        set(value) = preferences.edit { putString("eq_preset_$keySuffix", value.name) }

    var lastEnabledPreset: EqualizerPreset
        get() = preferences.getString("eq_last_$keySuffix", null)
            ?.let { stored -> EqualizerPreset.entries.firstOrNull { it.name == stored } }
            ?.takeUnless { it == EqualizerPreset.OFF }
            ?: EqualizerPreset.DIALOGUE
        set(value) {
            if (value != EqualizerPreset.OFF) {
                preferences.edit { putString("eq_last_$keySuffix", value.name) }
            }
        }

    var customBandLevels: List<Short>
        get() = preferences.getString("eq_custom_$keySuffix", null)
            ?.split(',')
            ?.mapNotNull(String::toShortOrNull)
            .orEmpty()
        set(value) = preferences.edit {
            putString("eq_custom_$keySuffix", value.joinToString(","))
        }

    var loudnessGainMb: Int
        get() = preferences.getInt("eq_loudness_$keySuffix", 0).coerceIn(0, 1_500)
        set(value) = preferences.edit { putInt("eq_loudness_$keySuffix", value.coerceIn(0, 1_500)) }

    var bassBoostStrength: Short
        get() = preferences.getInt("eq_bass_strength_$keySuffix", 0).coerceIn(0, 1_000).toShort()
        set(value) = preferences.edit { putInt("eq_bass_strength_$keySuffix", value.toInt().coerceIn(0, 1_000)) }

    var dspPreampMb: Int
        get() = preferences.getInt("dsp_preamp_$keySuffix", 0).coerceIn(-1_200, 600)
        set(value) = preferences.edit { putInt("dsp_preamp_$keySuffix", value.coerceIn(-1_200, 600)) }

    var dspAutoHeadroom: Boolean
        get() = preferences.getBoolean("dsp_auto_headroom_$keySuffix", true)
        set(value) = preferences.edit { putBoolean("dsp_auto_headroom_$keySuffix", value) }

    var dspLimiterCeilingMb: Int
        get() = preferences.getInt("dsp_limiter_ceiling_$keySuffix", -100).coerceIn(-600, -10)
        set(value) = preferences.edit { putInt("dsp_limiter_ceiling_$keySuffix", value.coerceIn(-600, -10)) }

    val defaultSubtitlesEnabled: Boolean
        get() = globalPreferences.playbackDefaults().subtitlesEnabled

    private companion object {
        const val PREFERENCES_NAME = "player_title_preferences"
    }
}

internal enum class EqualizerPreset(val title: String) {
    OFF("Выкл."),
    FLAT("Ровный"),
    DIALOGUE("Речь"),
    BASS("Бас"),
    BRIGHT("Ясность"),
    NIGHT("Ночной"),
    CINEMA("Кино"),
    LOUD("LOUD"),
    MAX("MAX"),
    CUSTOM("Свой"),
}

internal data class EqualizerBandState(
    val index: Short,
    val frequencyHz: Int,
    val levelMb: Short,
    val minimumMb: Short = -1_200,
    val maximumMb: Short = 1_200,
)

internal data class EqualizerUiState(
    val attached: Boolean = true,
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.OFF,
    val bands: List<EqualizerBandState> = emptyList(),
    val loudnessGainMb: Int = 0,
    val bassBoostStrength: Short = 0,
    val preampMb: Int = 0,
    val autoHeadroom: Boolean = true,
    val limiterCeilingMb: Int = -100,
    val inputPeakDb: Float = -120f,
    val outputPeakDb: Float = -120f,
    val rmsDb: Float = -120f,
    val limiterGainReductionDb: Float = 0f,
    val hardClipCount: Long = 0L,
    val message: String = "Встроенный DSP Media3 готов",
)

/**
 * UI/control facade for AnimeVault's own Media3 DSP chain.
 *
 * Unlike the old AudioFX implementation, this controller does not bind effects to an Android
 * audioSessionId. The actual processor lives inside DefaultAudioSink, so offline and native-online
 * playback get identical processing and the final limiter stays after Sonic speed processing.
 */
internal class PlayerEqualizerController(
    private val preferences: PlayerPreferences,
) {
    internal val audioEngine = com.sergey.animevault.ui.player.audio.AnimeVaultAudioEngine(
        configFromPreferences(preferences),
    )

    private val _state = mutableStateOf(stateFor(preferences.equalizerPreset))
    val state: State<EqualizerUiState> get() = _state

    fun attach(sessionId: Int) {
        // Kept as a compatibility no-op while old player call sites migrate away from AudioFX.
    }

    fun setEnabled(enabled: Boolean) {
        selectPreset(if (enabled) preferences.lastEnabledPreset else EqualizerPreset.OFF)
    }

    fun selectPreset(preset: EqualizerPreset) {
        preferences.equalizerPreset = preset
        preferences.lastEnabledPreset = preset
        val config = if (preset == EqualizerPreset.CUSTOM) {
            customConfigFromPreferences(preferences, audioEngine.config())
        } else {
            com.sergey.animevault.ui.player.audio.DspPresets.config(preset.name)
        }
        audioEngine.setConfig(config)
        if (preset != EqualizerPreset.CUSTOM) {
            preferences.loudnessGainMb = (config.loudnessPercent * 15f).roundToInt().coerceIn(0, 1_500)
            preferences.bassBoostStrength = (config.bassPercent * 10f).roundToInt().coerceIn(0, 1_000).toShort()
        }
        publishState(preset)
    }

    fun setBandLevel(index: Short, requestedMb: Short) {
        val safe = requestedMb.toInt().coerceIn(-1_200, 1_200).toShort()
        audioEngine.updateConfig { current ->
            val updated = current.eqBands.mapIndexed { bandIndex, band ->
                if (bandIndex == index.toInt()) band.copy(gainDb = safe / 100f) else band
            }
            current.copy(enabled = true, eqBands = updated)
        }
        preferences.equalizerPreset = EqualizerPreset.CUSTOM
        preferences.lastEnabledPreset = EqualizerPreset.CUSTOM
        preferences.customBandLevels = audioEngine.config().eqBands.map { (it.gainDb * 100f).roundToInt().toShort() }
        publishState(EqualizerPreset.CUSTOM)
    }

    fun setLoudnessGain(requestedMb: Int) {
        val safe = requestedMb.coerceIn(0, 1_500)
        val percent = safe / 15f
        preferences.loudnessGainMb = safe
        audioEngine.updateConfig { current ->
            current.copy(
                enabled = true,
                loudnessPercent = percent,
                dynamicsAmount = (percent / 100f * 0.94f).coerceIn(0f, 0.94f),
            )
        }
        markCustomAndPublish()
    }

    fun setBassBoostStrength(requested: Short) {
        val safe = requested.toInt().coerceIn(0, 1_000).toShort()
        preferences.bassBoostStrength = safe
        audioEngine.updateConfig { it.copy(enabled = true, bassPercent = safe / 10f) }
        markCustomAndPublish()
    }

    fun setPreampMb(requested: Int) {
        val safe = requested.coerceIn(-1_200, 600)
        preferences.dspPreampMb = safe
        audioEngine.updateConfig { it.copy(enabled = true, inputGainDb = safe / 100f) }
        markCustomAndPublish()
    }

    fun setAutoHeadroom(enabled: Boolean) {
        preferences.dspAutoHeadroom = enabled
        audioEngine.updateConfig { it.copy(autoHeadroom = enabled) }
        markCustomAndPublish()
    }

    fun setLimiterCeilingMb(requested: Int) {
        val safe = requested.coerceIn(-600, -10)
        preferences.dspLimiterCeilingMb = safe
        audioEngine.updateConfig { current -> current.copy(limiter = current.limiter.copy(ceilingDb = safe / 100f)) }
        markCustomAndPublish()
    }

    fun refreshMeters() {
        val meters = audioEngine.meters()
        _state.value = _state.value.copy(
            inputPeakDb = meters.inputPeakDb,
            outputPeakDb = meters.outputPeakDb,
            rmsDb = meters.rmsDb,
            limiterGainReductionDb = meters.limiterGainReductionDb,
            hardClipCount = meters.hardClipCount,
        )
    }

    fun release() {
        audioEngine.resetRuntimeState()
        refreshMeters()
    }

    private fun markCustomAndPublish() {
        preferences.equalizerPreset = EqualizerPreset.CUSTOM
        preferences.lastEnabledPreset = EqualizerPreset.CUSTOM
        preferences.customBandLevels = audioEngine.config().eqBands.map {
            (it.gainDb * 100f).roundToInt().coerceIn(-1_200, 1_200).toShort()
        }
        publishState(EqualizerPreset.CUSTOM)
    }

    private fun publishState(preset: EqualizerPreset) {
        _state.value = stateFor(preset)
    }

    private fun stateFor(preset: EqualizerPreset): EqualizerUiState {
        val config = audioEngine.config()
        val meters = audioEngine.meters()
        return EqualizerUiState(
            attached = true,
            enabled = config.enabled,
            preset = preset,
            bands = config.eqBands.mapIndexed { index, band ->
                EqualizerBandState(
                    index = index.toShort(),
                    frequencyHz = band.frequencyHz.roundToInt(),
                    levelMb = (band.gainDb * 100f).roundToInt().coerceIn(-1_200, 1_200).toShort(),
                )
            },
            loudnessGainMb = (config.loudnessPercent * 15f).roundToInt().coerceIn(0, 1_500),
            bassBoostStrength = (config.bassPercent * 10f).roundToInt().coerceIn(0, 1_000).toShort(),
            preampMb = (config.inputGainDb * 100f).roundToInt(),
            autoHeadroom = config.autoHeadroom,
            limiterCeilingMb = (config.limiter.ceilingDb * 100f).roundToInt(),
            inputPeakDb = meters.inputPeakDb,
            outputPeakDb = meters.outputPeakDb,
            rmsDb = meters.rmsDb,
            limiterGainReductionDb = meters.limiterGainReductionDb,
            hardClipCount = meters.hardClipCount,
            message = when (preset) {
                EqualizerPreset.LOUD -> "Высокая громкость: realtime-компрессия + look-ahead limiter"
                EqualizerPreset.MAX -> "Максимальная громкость: оптимизированная динамическая обработка"
                EqualizerPreset.OFF -> "DSP выключен; тракт остаётся безопасным для PCM16"
                else -> "Собственный realtime DSP работает внутри Media3, только для AnimeVault"
            },
        )
    }

    private companion object {
        fun configFromPreferences(preferences: PlayerPreferences): com.sergey.animevault.ui.player.audio.DspConfig {
            val preset = preferences.equalizerPreset
            val base = com.sergey.animevault.ui.player.audio.DspPresets.config(preset.name)
            return if (preset == EqualizerPreset.CUSTOM) customConfigFromPreferences(preferences, base) else base
        }

        fun customConfigFromPreferences(
            preferences: PlayerPreferences,
            fallback: com.sergey.animevault.ui.player.audio.DspConfig,
        ): com.sergey.animevault.ui.player.audio.DspConfig {
            val stored = preferences.customBandLevels
            val bands = fallback.eqBands.mapIndexed { index, band ->
                val mb = stored.getOrNull(index)?.toInt()
                if (mb != null) band.copy(gainDb = mb.coerceIn(-1_200, 1_200) / 100f) else band
            }
            val loudness = preferences.loudnessGainMb / 15f
            return fallback.copy(
                enabled = true,
                eqBands = bands,
                loudnessPercent = loudness,
                bassPercent = preferences.bassBoostStrength / 10f,
                inputGainDb = preferences.dspPreampMb / 100f,
                autoHeadroom = preferences.dspAutoHeadroom,
                dynamicsAmount = (loudness / 100f * 0.94f).coerceIn(0f, 0.94f),
                limiter = fallback.limiter.copy(ceilingDb = preferences.dspLimiterCeilingMb / 100f),
            ).normalized()
        }
    }
}

@Composable
internal fun EqualizerSheet(
    controller: PlayerEqualizerController,
    onDismiss: () -> Unit,
) {
    val state by controller.state
    LaunchedEffect(controller) {
        while (true) {
            controller.refreshMeters()
            kotlinx.coroutines.delay(100L)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
        ) {
            VaultSheetHeader(
                title = "Audio Engine",
                subtitle = "PEQ · 3-band dynamics · realtime look-ahead limiter",
                modifier = Modifier.padding(bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("DSP", fontWeight = FontWeight.SemiBold)
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.74f))
                        }
                        Switch(checked = state.enabled, onCheckedChange = controller::setEnabled)
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EqualizerPreset.entries.filterNot { it == EqualizerPreset.CUSTOM }, key = EqualizerPreset::name) { preset ->
                            FilterChip(
                                selected = state.preset == preset,
                                onClick = { controller.selectPreset(preset) },
                                label = { Text(preset.title) },
                            )
                        }
                    }
                }
                if (state.enabled) {
                    item {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Громкость DSP")
                                Text("${(state.loudnessGainMb / 15f).roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = state.loudnessGainMb.toFloat(),
                                onValueChange = { controller.setLoudnessGain(it.roundToInt()) },
                                valueRange = 0f..1_500f,
                            )
                        }
                    }
                    item {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Бас")
                                Text("${state.bassBoostStrength.toInt() / 10}%")
                            }
                            Slider(
                                value = state.bassBoostStrength.toFloat(),
                                onValueChange = { controller.setBassBoostStrength(it.roundToInt().toShort()) },
                                valueRange = 0f..1_000f,
                            )
                        }
                    }
                    items(state.bands, key = EqualizerBandState::index) { band ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatFrequency(band.frequencyHz))
                                Text(formatDecibels(band.levelMb), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = band.levelMb.toFloat(),
                                onValueChange = { controller.setBandLevel(band.index, it.roundToInt().toShort()) },
                                valueRange = band.minimumMb.toFloat()..band.maximumMb.toFloat(),
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto Headroom")
                                Text("Автоматически освобождает запас под подъём EQ", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.68f))
                            }
                            Switch(checked = state.autoHeadroom, onCheckedChange = controller::setAutoHeadroom)
                        }
                    }
                    item {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Preamp")
                                Text(formatDecibels(state.preampMb.toShort()))
                            }
                            Slider(
                                value = state.preampMb.toFloat(),
                                onValueChange = { controller.setPreampMb(it.roundToInt()) },
                                valueRange = -1_200f..600f,
                            )
                        }
                    }
                    item {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Limiter ceiling")
                                Text(formatDecibels(state.limiterCeilingMb.toShort()))
                            }
                            Slider(
                                value = state.limiterCeilingMb.toFloat(),
                                onValueChange = { controller.setLimiterCeilingMb(it.roundToInt()) },
                                valueRange = -600f..-10f,
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Уровни", fontWeight = FontWeight.SemiBold)
                            Text("IN ${formatDbFloat(state.inputPeakDb)} · OUT ${formatDbFloat(state.outputPeakDb)} · RMS ${formatDbFloat(state.rmsDb)}")
                            Text("Limiter GR −${String.format(Locale.ROOT, "%.1f", state.limiterGainReductionDb)} dB · hard clips ${state.hardClipCount}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text(
                        "LOUD и MAX повышают прежде всего средний уровень через компрессию и makeup, а не простым +dB. " +
                            "Финальный limiter расположен после изменения скорости воспроизведения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Готово") }
        }
    }
}

internal data class PresetAudioTuning(
    val loudnessGainMb: Int,
    val bassBoostStrength: Short,
)

internal fun presetAudioTuning(preset: EqualizerPreset): PresetAudioTuning {
    val config = com.sergey.animevault.ui.player.audio.DspPresets.config(preset.name)
    return PresetAudioTuning(
        loudnessGainMb = (config.loudnessPercent * 15f).roundToInt().coerceIn(0, 1_500),
        bassBoostStrength = (config.bassPercent * 10f).roundToInt().coerceIn(0, 1_000).toShort(),
    )
}

internal fun presetLevelMb(preset: EqualizerPreset, frequencyHz: Int): Short {
    val config = com.sergey.animevault.ui.player.audio.DspPresets.config(preset.name)
    val nearest = config.eqBands.minByOrNull { kotlin.math.abs(it.frequencyHz - frequencyHz) } ?: return 0
    return (nearest.gainDb * 100f).roundToInt().coerceIn(-1_200, 1_200).toShort()
}

private fun formatFrequency(frequencyHz: Int): String = if (frequencyHz >= 1_000) {
    String.format(Locale.ROOT, "%.1f кГц", frequencyHz / 1_000f)
} else {
    "$frequencyHz Гц"
}

private fun formatDecibels(levelMb: Short): String = String.format(Locale.ROOT, "%+.1f дБ", levelMb / 100f)

private fun formatDbFloat(value: Float): String = String.format(Locale.ROOT, "%+.1f dB", value)

private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .take(12)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
