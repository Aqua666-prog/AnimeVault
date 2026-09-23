package com.sergey.animevault.ui.preferences

import android.content.Context
import androidx.core.content.edit
import com.sergey.animevault.ui.library.LibraryLayout
import com.sergey.animevault.ui.online.CatalogLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<AppearanceSettings> = _appearance.asStateFlow()

    private val _playbackDefaults = MutableStateFlow(readPlaybackDefaults())
    val playbackDefaultsState: StateFlow<PlaybackDefaults> = _playbackDefaults.asStateFlow()

    fun libraryLayout(): LibraryLayout = enumValue(
        key = KEY_LIBRARY_LAYOUT,
        default = LibraryLayout.POSTER_GRID,
    )

    fun setLibraryLayout(layout: LibraryLayout) {
        preferences.edit { putString(KEY_LIBRARY_LAYOUT, layout.name) }
    }

    fun onlineLayout(): CatalogLayout = enumValue(
        key = KEY_ONLINE_LAYOUT,
        default = CatalogLayout.GRID,
    )

    fun setOnlineLayout(layout: CatalogLayout) {
        preferences.edit { putString(KEY_ONLINE_LAYOUT, layout.name) }
    }

    fun appearanceSettings(): AppearanceSettings = _appearance.value

    fun setThemeMode(value: VaultThemeMode) = updateAppearance { copy(theme = value) }
    fun setAccentMode(value: VaultAccentMode) = updateAppearance { copy(accent = value) }
    fun setBlurEnabled(value: Boolean) = updateAppearance { copy(blurEnabled = value) }
    fun setMotionMode(value: VaultMotionMode) = updateAppearance { copy(motion = value) }

    fun playbackDefaults(): PlaybackDefaults = _playbackDefaults.value

    fun setDefaultSpeed(value: Float) = updatePlaybackDefaults {
        copy(speed = value.coerceIn(0.5f, 2f))
    }

    fun setDefaultVideoScale(value: DefaultVideoScale) = updatePlaybackDefaults {
        copy(videoScale = value)
    }

    fun setDefaultNextEpisode(value: DefaultNextEpisode) = updatePlaybackDefaults {
        copy(nextEpisode = value)
    }

    fun setDefaultEqualizer(value: DefaultEqualizer) = updatePlaybackDefaults {
        copy(equalizer = value)
    }

    fun setDefaultSubtitlesEnabled(value: Boolean) = updatePlaybackDefaults {
        copy(subtitlesEnabled = value)
    }

    fun onlineSearchHistory(): List<String> {
        val raw = preferences.getString(KEY_ONLINE_SEARCH_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList<String> {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordOnlineSearch(query: String): List<String> {
        val updated = mergeSearchHistory(
            existing = onlineSearchHistory(),
            query = query,
            maxItems = MAX_SEARCH_HISTORY,
        )
        writeSearchHistory(updated)
        return updated
    }

    fun removeOnlineSearch(query: String): List<String> {
        val updated = removeSearchHistoryItem(onlineSearchHistory(), query)
        writeSearchHistory(updated)
        return updated
    }

    fun clearOnlineSearchHistory() {
        preferences.edit { remove(KEY_ONLINE_SEARCH_HISTORY) }
    }

    private fun updateAppearance(transform: AppearanceSettings.() -> AppearanceSettings) {
        val updated = _appearance.value.transform()
        preferences.edit {
            putString(KEY_THEME_MODE, updated.theme.name)
            putString(KEY_ACCENT_MODE, updated.accent.name)
            putBoolean(KEY_BLUR_ENABLED, updated.blurEnabled)
            putString(KEY_MOTION_MODE, updated.motion.name)
        }
        _appearance.value = updated
    }

    private fun readAppearance(): AppearanceSettings = AppearanceSettings(
        theme = enumValue(KEY_THEME_MODE, VaultThemeMode.VAULT),
        accent = enumValue(KEY_ACCENT_MODE, VaultAccentMode.VIOLET),
        blurEnabled = preferences.getBoolean(KEY_BLUR_ENABLED, true),
        motion = enumValue(KEY_MOTION_MODE, VaultMotionMode.FULL),
    )

    private fun updatePlaybackDefaults(transform: PlaybackDefaults.() -> PlaybackDefaults) {
        val updated = _playbackDefaults.value.transform()
        preferences.edit {
            putFloat(KEY_DEFAULT_SPEED, updated.speed)
            putString(KEY_DEFAULT_VIDEO_SCALE, updated.videoScale.name)
            putString(KEY_DEFAULT_NEXT_EPISODE, updated.nextEpisode.name)
            putString(KEY_DEFAULT_EQUALIZER, updated.equalizer.name)
            putBoolean(KEY_DEFAULT_SUBTITLES, updated.subtitlesEnabled)
        }
        _playbackDefaults.value = updated
    }

    private fun readPlaybackDefaults(): PlaybackDefaults = PlaybackDefaults(
        speed = preferences.getFloat(KEY_DEFAULT_SPEED, 1f).coerceIn(0.5f, 2f),
        videoScale = enumValue(KEY_DEFAULT_VIDEO_SCALE, DefaultVideoScale.FIT),
        nextEpisode = enumValue(KEY_DEFAULT_NEXT_EPISODE, DefaultNextEpisode.COUNTDOWN),
        equalizer = enumValue(KEY_DEFAULT_EQUALIZER, DefaultEqualizer.OFF),
        subtitlesEnabled = preferences.getBoolean(KEY_DEFAULT_SUBTITLES, true),
    )

    private fun writeSearchHistory(values: List<String>) {
        val array = JSONArray()
        values.forEach(array::put)
        preferences.edit { putString(KEY_ONLINE_SEARCH_HISTORY, array.toString()) }
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T {
        val raw = preferences.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }

    private companion object {
        const val PREFERENCES_NAME = "anime_vault_ui"
        const val KEY_LIBRARY_LAYOUT = "library_layout"
        const val KEY_ONLINE_LAYOUT = "online_layout"
        const val KEY_ONLINE_SEARCH_HISTORY = "online_search_history"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_MODE = "accent_mode"
        const val KEY_BLUR_ENABLED = "blur_enabled"
        const val KEY_MOTION_MODE = "motion_mode"
        const val KEY_DEFAULT_SPEED = "player_default_speed"
        const val KEY_DEFAULT_VIDEO_SCALE = "player_default_video_scale"
        const val KEY_DEFAULT_NEXT_EPISODE = "player_default_next_episode"
        const val KEY_DEFAULT_EQUALIZER = "player_default_equalizer"
        const val KEY_DEFAULT_SUBTITLES = "player_default_subtitles"
        const val MAX_SEARCH_HISTORY = 8
    }
}
