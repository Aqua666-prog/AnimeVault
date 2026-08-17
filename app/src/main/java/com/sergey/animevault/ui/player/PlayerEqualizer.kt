package com.sergey.animevault.ui.player

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
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

    var speed: Float
        get() = preferences.getFloat("speed_$keySuffix", 1f).coerceIn(0.5f, 2f)
        set(value) = preferences.edit { putFloat("speed_$keySuffix", value.coerceIn(0.5f, 2f)) }

    var videoScaleMode: VideoScaleMode
        get() = preferences.getString("video_scale_$keySuffix", null)
            ?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
            ?: VideoScaleMode.FIT
        set(value) = preferences.edit { putString("video_scale_$keySuffix", value.name) }

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

    var equalizerPreset: EqualizerPreset
        get() = preferences.getString("eq_preset_$keySuffix", null)
            ?.let { stored -> EqualizerPreset.entries.firstOrNull { it.name == stored } }
            ?: EqualizerPreset.OFF
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
    CUSTOM("Свой"),
}

internal data class EqualizerBandState(
    val index: Short,
    val frequencyHz: Int,
    val levelMb: Short,
    val minimumMb: Short,
    val maximumMb: Short,
)

internal data class EqualizerUiState(
    val attached: Boolean = false,
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.OFF,
    val bands: List<EqualizerBandState> = emptyList(),
    val message: String = "Эквалайзер подключится после запуска звука",
)

/**
 * Обёртка над системным Equalizer. Эффект привязывается только к audioSessionId
 * конкретного ExoPlayer и никогда не меняет звук всего телефона.
 */
internal class PlayerEqualizerController(
    private val preferences: PlayerPreferences,
) {
    private val _state = mutableStateOf(
        EqualizerUiState(
            enabled = preferences.equalizerPreset != EqualizerPreset.OFF,
            preset = preferences.equalizerPreset,
        ),
    )
    val state: State<EqualizerUiState> get() = _state

    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = 0

    fun attach(sessionId: Int) {
        if (sessionId <= 0 || sessionId == audioSessionId && equalizer != null) return
        releaseEffectOnly()
        audioSessionId = sessionId
        runCatching {
            Equalizer(0, sessionId).also { effect ->
                equalizer = effect
                applyPreset(preferences.equalizerPreset, persist = false)
            }
        }.onFailure { error ->
            equalizer = null
            audioSessionId = 0
            _state.value = EqualizerUiState(
                preset = preferences.equalizerPreset,
                message = when (error) {
                    is UnsupportedOperationException -> "Эквалайзер не поддерживается аудиодрайвером устройства"
                    else -> "Не удалось подключить эквалайзер: ${error.message ?: "ошибка аудиодрайвера"}"
                },
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        applyPreset(if (enabled) preferences.lastEnabledPreset else EqualizerPreset.OFF)
    }

    fun selectPreset(preset: EqualizerPreset) {
        applyPreset(preset)
    }

    fun setBandLevel(index: Short, requestedMb: Short) {
        val effect = equalizer ?: return
        val range = effect.bandLevelRange
        val safeLevel = requestedMb.coerceIn(range[0], range[1])
        runCatching {
            effect.enabled = true
            effect.setBandLevel(index, safeLevel)
            preferences.equalizerPreset = EqualizerPreset.CUSTOM
            preferences.lastEnabledPreset = EqualizerPreset.CUSTOM
            val levels = (0 until effect.numberOfBands.toInt()).map { band ->
                effect.getBandLevel(band.toShort())
            }
            preferences.customBandLevels = levels
            publishState(EqualizerPreset.CUSTOM)
        }.onFailure(::publishError)
    }

    fun release() {
        releaseEffectOnly()
        audioSessionId = 0
        _state.value = _state.value.copy(
            attached = false,
            bands = emptyList(),
            message = "Эквалайзер подключится после запуска звука",
        )
    }

    private fun applyPreset(preset: EqualizerPreset, persist: Boolean = true) {
        if (persist) {
            preferences.equalizerPreset = preset
            preferences.lastEnabledPreset = preset
        }
        val effect = equalizer
        if (effect == null) {
            _state.value = _state.value.copy(
                enabled = preset != EqualizerPreset.OFF,
                preset = preset,
            )
            return
        }
        runCatching {
            if (preset == EqualizerPreset.OFF) {
                effect.enabled = false
                publishState(preset)
                return@runCatching
            }
            effect.enabled = true
            val range = effect.bandLevelRange
            val custom = preferences.customBandLevels
            repeat(effect.numberOfBands.toInt()) { rawIndex ->
                val index = rawIndex.toShort()
                val level = when (preset) {
                    EqualizerPreset.CUSTOM -> custom.getOrNull(rawIndex) ?: 0
                    else -> presetLevelMb(
                        preset = preset,
                        frequencyHz = effect.getCenterFreq(index) / 1_000,
                    )
                }.coerceIn(range[0], range[1])
                effect.setBandLevel(index, level)
            }
            publishState(preset)
        }.onFailure(::publishError)
    }

    private fun publishState(preset: EqualizerPreset) {
        val effect = equalizer ?: return
        val range = effect.bandLevelRange
        _state.value = EqualizerUiState(
            attached = true,
            enabled = effect.enabled,
            preset = preset,
            bands = (0 until effect.numberOfBands.toInt()).map { rawIndex ->
                val index = rawIndex.toShort()
                EqualizerBandState(
                    index = index,
                    frequencyHz = effect.getCenterFreq(index) / 1_000,
                    levelMb = effect.getBandLevel(index),
                    minimumMb = range[0],
                    maximumMb = range[1],
                )
            },
            message = "Эквалайзер действует только на текущий тайтл",
        )
    }

    private fun publishError(error: Throwable) {
        _state.value = _state.value.copy(
            attached = equalizer != null,
            message = "Ошибка эквалайзера: ${error.message ?: "аудиоэффект недоступен"}",
        )
    }

    private fun releaseEffectOnly() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}

@Composable
internal fun EqualizerDialog(
    controller: PlayerEqualizerController,
    onDismiss: () -> Unit,
) {
    val state by controller.state
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Эквалайзер") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Улучшение звука", fontWeight = FontWeight.SemiBold)
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f),
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = controller::setEnabled,
                            enabled = state.attached,
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            EqualizerPreset.entries.filterNot { it == EqualizerPreset.CUSTOM },
                            key = EqualizerPreset::name,
                        ) { preset ->
                            FilterChip(
                                selected = state.preset == preset,
                                onClick = { controller.selectPreset(preset) },
                                enabled = state.attached,
                                label = { Text(preset.title) },
                            )
                        }
                    }
                }
                if (state.attached && state.enabled) {
                    items(state.bands, key = EqualizerBandState::index) { band ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(formatFrequency(band.frequencyHz))
                                Text(
                                    formatDecibels(band.levelMb),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Slider(
                                value = band.levelMb.toFloat(),
                                onValueChange = { value ->
                                    controller.setBandLevel(band.index, value.roundToInt().toShort())
                                },
                                valueRange = band.minimumMb.toFloat()..band.maximumMb.toFloat(),
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Сильное усиление нескольких полос может вызвать хрип. " +
                            "Для обычной речи начните с пресета «Речь».",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
    )
}

internal fun presetLevelMb(preset: EqualizerPreset, frequencyHz: Int): Short {
    val db = when (preset) {
        EqualizerPreset.FLAT, EqualizerPreset.OFF, EqualizerPreset.CUSTOM -> 0f
        EqualizerPreset.DIALOGUE -> when {
            frequencyHz < 180 -> -2.0f
            frequencyHz < 500 -> 0.5f
            frequencyHz < 1_500 -> 2.5f
            frequencyHz < 4_500 -> 3.5f
            else -> 1.0f
        }
        EqualizerPreset.BASS -> when {
            frequencyHz < 120 -> 4.0f
            frequencyHz < 350 -> 3.0f
            frequencyHz < 1_200 -> 0.5f
            else -> -0.5f
        }
        EqualizerPreset.BRIGHT -> when {
            frequencyHz < 250 -> -1.5f
            frequencyHz < 1_500 -> 0.5f
            frequencyHz < 5_000 -> 2.5f
            else -> 3.5f
        }
        EqualizerPreset.NIGHT -> when {
            frequencyHz < 180 -> -3.0f
            frequencyHz < 800 -> 1.0f
            frequencyHz < 4_000 -> 3.0f
            else -> 0.5f
        }
    }
    return (db * 100).roundToInt().toShort()
}

private fun formatFrequency(frequencyHz: Int): String = if (frequencyHz >= 1_000) {
    String.format(Locale.ROOT, "%.1f кГц", frequencyHz / 1_000f)
} else {
    "$frequencyHz Гц"
}

private fun formatDecibels(levelMb: Short): String = String.format(
    Locale.ROOT,
    "%+.1f дБ",
    levelMb / 100f,
)

private fun String.sha256Prefix(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .take(12)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
