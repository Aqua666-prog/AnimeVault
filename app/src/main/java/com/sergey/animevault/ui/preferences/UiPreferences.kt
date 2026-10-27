package com.sergey.animevault.ui.preferences

import android.content.Context
import androidx.core.content.edit
import com.sergey.animevault.ui.library.LibraryLayout
import com.sergey.animevault.ui.online.CatalogLayout
import com.sergey.animevault.data.notifications.EpisodeAlertScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class UiPreferences(context: Context) {
    private val preferencesContext = context.applicationContext
    private val preferences = preferencesContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** Explicit singleton overrides: source records and their histories remain untouched. */
    fun keepReleaseSeparate(providerId: String, releaseId: String): Boolean =
        com.sergey.animevault.data.titles.providerTitleKey(providerId, releaseId) in
            preferences.getStringSet("separate_online_releases", emptySet()).orEmpty()

    @Synchronized
    fun setReleaseSeparate(providerId: String, releaseId: String, separate: Boolean) {
        val key = com.sergey.animevault.data.titles.providerTitleKey(providerId, releaseId)
        val next = preferences.getStringSet("separate_online_releases", emptySet()).orEmpty().toMutableSet()
        if (separate) next.add(key) else next.remove(key)
        preferences.edit { putStringSet("separate_online_releases", next) }
    }

    var automaticProviderSwitch: Boolean
        get() = preferences.getBoolean("automatic_provider_switch", false)
        set(value) = preferences.edit { putBoolean("automatic_provider_switch", value) }

    fun episodeDecision(a: String, b: String): Boolean? {
        val key = "episode_match_" + listOf(a, b).sorted().joinToString("_")
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }
    fun clearEpisodeDecision(a: String, b: String) {
        preferences.edit { remove("episode_match_" + listOf(a, b).sorted().joinToString("_")) }
    }

    fun setEpisodeDecision(a: String, b: String, match: Boolean) {
        val key = "episode_match_" + listOf(a, b).sorted().joinToString("_")
        preferences.edit { putBoolean(key, match) }
    }

    private val _titleLanguage = MutableStateFlow(com.sergey.animevault.data.titles.AnimeTitleLanguage.fromStored(preferences.getString("anime_title_language", null)))
    val titleLanguage = _titleLanguage.asStateFlow()
    fun setTitleLanguage(value: com.sergey.animevault.data.titles.AnimeTitleLanguage) {
        preferences.edit { putString("anime_title_language", value.name) }
        _titleLanguage.value = value
    }

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<AppearanceSettings> = _appearance.asStateFlow()

    private val _titleLists = MutableStateFlow(readTitleLists())
    val titleLists: StateFlow<Map<String, VaultTitleList>> = _titleLists.asStateFlow()
    private val _localFavorites = MutableStateFlow(preferences.getStringSet(KEY_LOCAL_FAVORITES, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet())
    val localFavorites: StateFlow<Set<Long>> = _localFavorites.asStateFlow()

    private val _personalTitles = MutableStateFlow(VaultPersonalCodec.titlesFromJson(preferences.getString(KEY_PERSONAL_TITLES, null)))
    val personalTitles: StateFlow<Map<String, VaultPersonalTitle>> = _personalTitles.asStateFlow()
    private val _catalogPresets = MutableStateFlow(VaultPersonalCodec.presetsFromJson(preferences.getString(KEY_CATALOG_PRESETS, null)))
    val catalogPresets: StateFlow<List<CatalogPreset>> = _catalogPresets.asStateFlow()
    private val _episodeAlertLists = MutableStateFlow(
        preferences.getStringSet(KEY_EPISODE_ALERT_LISTS, emptySet()).orEmpty()
            .mapNotNull { name -> VaultTitleList.entries.firstOrNull { it.name == name && it != VaultTitleList.NONE } }.toSet())
    val episodeAlertLists: StateFlow<Set<VaultTitleList>> = _episodeAlertLists.asStateFlow()

    fun setEpisodeAlertLists(statuses: Set<VaultTitleList>) {
        val filtered = statuses.filter { it != VaultTitleList.NONE }.toSet()
        preferences.edit { putStringSet(KEY_EPISODE_ALERT_LISTS, filtered.map { it.name }.toSet()) }
        _episodeAlertLists.value = filtered
        EpisodeAlertScheduler.reconcile(preferencesContext, this)
    }

    @Synchronized
    private fun updatePersonal(key: String, change: (VaultPersonalTitle) -> VaultPersonalTitle) {
        if (key.isBlank()) return
        val next = _personalTitles.value.toMutableMap()
        next[key] = change(next[key] ?: VaultPersonalTitle()).copy(updatedAt = System.currentTimeMillis())
        preferences.edit { putString(KEY_PERSONAL_TITLES, VaultPersonalCodec.titlesToJson(next)) }
        _personalTitles.value = next.toMap()
    }

    fun setPersonalRating(key: String, rating: Int?) {
        require(rating == null || rating in 1..5)
        updatePersonal(key) { it.copy(rating = rating) }
    }

    fun setPersonalNote(key: String, note: String) = updatePersonal(key) { it.copy(note = note.take(2000)) }

    fun setEpisodeAlerts(key: String, enabled: Boolean, translationKey: String?) {
        updatePersonal(key) {
            it.copy(notifyEpisodes = enabled, translationKey = if (enabled) translationKey else null,
                baselineReady = false, seenEpisodeIds = emptyList())
        }
        EpisodeAlertScheduler.reconcile(preferencesContext, this)
    }

    /** Do not modify the user's note/rating timestamp when just advancing the polling baseline. */
    @Synchronized
    fun updateEpisodeBaseline(key: String, ids: List<String>) {
        val current = _personalTitles.value[key] ?: VaultPersonalTitle()
        if (!current.notifyEpisodes && _titleLists.value[key] !in _episodeAlertLists.value) return
        val next = _personalTitles.value + (key to current.copy(seenEpisodeIds = ids.distinct().takeLast(500), baselineReady = true))
        preferences.edit { putString(KEY_PERSONAL_TITLES, VaultPersonalCodec.titlesToJson(next)) }
        _personalTitles.value = next
    }

    @Synchronized
    fun replacePersonalTitles(restored: Map<String, VaultPersonalTitle>) {
        val filtered = restored.filter { (key, entry) -> key.isNotBlank() && (entry.rating == null || entry.rating in 1..5) }
        preferences.edit { putString(KEY_PERSONAL_TITLES, VaultPersonalCodec.titlesToJson(filtered)) }
        _personalTitles.value = filtered
        EpisodeAlertScheduler.reconcile(preferencesContext, this)
    }

    fun saveCatalogPreset(preset: CatalogPreset) {
        val next = (_catalogPresets.value.filterNot { it.providerId == preset.providerId && it.name == preset.name } + preset)
            .takeLast(30)
        replaceCatalogPresets(next)
    }

    fun deleteCatalogPreset(preset: CatalogPreset) = replaceCatalogPresets(
        _catalogPresets.value.filterNot { it.providerId == preset.providerId && it.name == preset.name })

    fun replaceCatalogPresets(restored: List<CatalogPreset>) {
        val next = restored.filter { it.name.isNotBlank() && it.providerId.isNotBlank() }.takeLast(30)
        preferences.edit { putString(KEY_CATALOG_PRESETS, VaultPersonalCodec.presetsToJson(next)) }
        _catalogPresets.value = next
    }

    fun setTitleList(key: String, list: VaultTitleList) {
        val updated = _titleLists.value.toMutableMap()
        if (list == VaultTitleList.NONE) updated.remove(key) else updated[key] = list
        val json = JSONObject()
        updated.forEach { (id, value) -> json.put(id, value.name) }
        preferences.edit { putString(KEY_TITLE_LISTS, json.toString()) }
        _titleLists.value = updated.toMap()
        if (_episodeAlertLists.value.isNotEmpty()) EpisodeAlertScheduler.reconcile(preferencesContext, this)
    }

    /** Replace list state after backup merge. Old string enum names remain unchanged. */
    fun replaceTitleLists(restored: Map<String, VaultTitleList>) {
        val filtered = restored.filter { (key, status) -> key.isNotBlank() && status != VaultTitleList.NONE }
        val json = JSONObject()
        filtered.forEach { (key, status) -> json.put(key, status.name) }
        preferences.edit { putString(KEY_TITLE_LISTS, json.toString()) }
        _titleLists.value = filtered
        if (_episodeAlertLists.value.isNotEmpty()) EpisodeAlertScheduler.reconcile(preferencesContext, this)
    }

    /** Favorite flags are intentionally independent from list membership and progress. */
    fun replaceLocalFavorites(restored: Set<Long>) {
        val filtered = restored.filter { it > 0L }.toSet()
        preferences.edit { putStringSet(KEY_LOCAL_FAVORITES, filtered.map(Long::toString).toSet()) }
        _localFavorites.value = filtered
    }

    fun libraryHubTab(): String = preferences.getString(KEY_LIBRARY_HUB_TAB, "CONTINUE") ?: "CONTINUE"

    fun setLibraryHubTab(value: String) {
        preferences.edit { putString(KEY_LIBRARY_HUB_TAB, value) }
    }

    fun setLocalFavorite(id: Long, favorite: Boolean) {
        val updated = if (favorite) _localFavorites.value + id else _localFavorites.value - id
        preferences.edit { putStringSet(KEY_LOCAL_FAVORITES, updated.map(Long::toString).toSet()) }
        _localFavorites.value = updated
    }

    private fun readTitleLists(): Map<String, VaultTitleList> = runCatching {
        val json = JSONObject(preferences.getString(KEY_TITLE_LISTS, null) ?: "{}")
        json.keys().asSequence().mapNotNull { key ->
            VaultTitleList.entries.firstOrNull { it.name == json.optString(key) && it != VaultTitleList.NONE }?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

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
    fun setDynamicArtwork(value: Boolean) = updateAppearance { copy(dynamicArtwork = value) }
    fun setReducedMotion(value: Boolean) = updateAppearance { copy(reducedMotion = value) }

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

    fun setOnlineQuality(value: Int?) = updatePlaybackDefaults {
        copy(onlineQuality = value?.takeIf { it in VIDEO_QUALITY_OPTIONS })
    }

    fun setDownloadQuality(value: Int?) = updatePlaybackDefaults {
        copy(downloadQuality = value?.takeIf { it in VIDEO_QUALITY_OPTIONS })
    }

    fun setAutoNextSeason(value: Boolean) = updatePlaybackDefaults {
        copy(autoNextSeason = value)
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
            putBoolean(KEY_DYNAMIC_ARTWORK, updated.dynamicArtwork)
            putBoolean(KEY_REDUCED_MOTION, updated.reducedMotion)
        }
        _appearance.value = updated
    }

    private fun readAppearance(): AppearanceSettings = AppearanceSettings(
        theme = enumValue(KEY_THEME_MODE, VaultThemeMode.VAULT).let { if (it == VaultThemeMode.OLED) it else VaultThemeMode.VAULT },
        accent = enumValue(KEY_ACCENT_MODE, VaultAccentMode.VIOLET),
        blurEnabled = preferences.getBoolean(KEY_BLUR_ENABLED, false),
        motion = enumValue(KEY_MOTION_MODE, VaultMotionMode.FULL),
        dynamicArtwork = preferences.getBoolean(KEY_DYNAMIC_ARTWORK, false),
        reducedMotion = preferences.getBoolean(KEY_REDUCED_MOTION, false),
    )

    private fun updatePlaybackDefaults(transform: PlaybackDefaults.() -> PlaybackDefaults) {
        val updated = _playbackDefaults.value.transform()
        preferences.edit {
            putFloat(KEY_DEFAULT_SPEED, updated.speed)
            putString(KEY_DEFAULT_VIDEO_SCALE, updated.videoScale.name)
            putString(KEY_DEFAULT_NEXT_EPISODE, updated.nextEpisode.name)
            putString(KEY_DEFAULT_EQUALIZER, updated.equalizer.name)
            putBoolean(KEY_DEFAULT_SUBTITLES, updated.subtitlesEnabled)
            putInt(KEY_ONLINE_QUALITY, updated.onlineQuality ?: 0)
            putInt(KEY_DOWNLOAD_QUALITY, updated.downloadQuality ?: 0)
            putBoolean(KEY_AUTO_NEXT_SEASON, updated.autoNextSeason)
        }
        _playbackDefaults.value = updated
    }

    private fun readPlaybackDefaults(): PlaybackDefaults = PlaybackDefaults(
        speed = preferences.getFloat(KEY_DEFAULT_SPEED, 1f).coerceIn(0.5f, 2f),
        videoScale = enumValue(KEY_DEFAULT_VIDEO_SCALE, DefaultVideoScale.FIT),
        nextEpisode = enumValue(KEY_DEFAULT_NEXT_EPISODE, DefaultNextEpisode.COUNTDOWN),
        equalizer = enumValue(KEY_DEFAULT_EQUALIZER, DefaultEqualizer.OFF),
        subtitlesEnabled = preferences.getBoolean(KEY_DEFAULT_SUBTITLES, true),
        onlineQuality = preferences.getInt(KEY_ONLINE_QUALITY, 0).takeIf { it in VIDEO_QUALITY_OPTIONS },
        downloadQuality = preferences.getInt(KEY_DOWNLOAD_QUALITY, 1080).takeIf { it in VIDEO_QUALITY_OPTIONS },
        autoNextSeason = preferences.getBoolean(KEY_AUTO_NEXT_SEASON, true),
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
        const val KEY_LIBRARY_HUB_TAB = "library_hub_tab_v1"
        const val KEY_ONLINE_LAYOUT = "online_layout"
        const val KEY_ONLINE_SEARCH_HISTORY = "online_search_history"
        const val KEY_TITLE_LISTS = "title_lists_v1"
        const val KEY_PERSONAL_TITLES = "personal_titles_v1"
        const val KEY_CATALOG_PRESETS = "catalog_presets_v1"
        const val KEY_EPISODE_ALERT_LISTS = "episode_alert_lists_v1"
        const val KEY_LOCAL_FAVORITES = "local_favorites_v1"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_MODE = "accent_mode"
        const val KEY_BLUR_ENABLED = "blur_enabled"
        const val KEY_MOTION_MODE = "motion_mode"
        const val KEY_DYNAMIC_ARTWORK = "dynamic_artwork"
        const val KEY_REDUCED_MOTION = "reduced_motion"
        const val KEY_DEFAULT_SPEED = "player_default_speed"
        const val KEY_DEFAULT_VIDEO_SCALE = "player_default_video_scale"
        const val KEY_DEFAULT_NEXT_EPISODE = "player_default_next_episode"
        const val KEY_DEFAULT_EQUALIZER = "player_default_equalizer"
        const val KEY_DEFAULT_SUBTITLES = "player_default_subtitles"
        const val KEY_ONLINE_QUALITY = "playback_quality_online_v3"
        const val KEY_DOWNLOAD_QUALITY = "download_quality_v3"
        const val KEY_AUTO_NEXT_SEASON = "autoplay_next_season_v3"
        val VIDEO_QUALITY_OPTIONS = setOf(360, 480, 720, 1080, 1440, 2160)
        const val MAX_SEARCH_HISTORY = 8
    }
}
