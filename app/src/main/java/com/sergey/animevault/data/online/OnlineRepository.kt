package com.sergey.animevault.data.online

import android.content.Context
import androidx.core.content.edit
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope

class OnlineRepository(
    context: Context,
    providers: List<OnlineProvider>,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val endpointRegistry: ProviderEndpointRegistry? = null,
) {
    private val providerMap = providers.associateBy { it.descriptor.id }
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val progressStore = OnlineProgressStore(context)
    private val libraryStore = OnlineLibraryStore(context)
    private val catalogRequests = InFlightRequestCache<CatalogRequestKey, OnlineCatalogPage>(
        maxEntries = 96,
        ttlMs = 30_000L,
    )
    private val releaseRequests = InFlightRequestCache<ReleaseRequestKey, OnlineReleaseDetails>(
        maxEntries = 128,
        ttlMs = 5 * 60_000L,
    )
    private val streamRequests = InFlightRequestCache<StreamRequestKey, List<OnlineStream>>(
        maxEntries = 64,
        ttlMs = 0L,
    )
    private val _activeProviderId = MutableStateFlow(
        preferences.getString(ACTIVE_PROVIDER_KEY, null)
            ?.takeIf(providerMap::containsKey)
            ?: providers.firstOrNull()?.descriptor?.id
            ?: error("At least one online provider is required"),
    )
    private val _accountStates = MutableStateFlow(readAccountStates())
    private val _preferredTranslations = MutableStateFlow(readPreferredTranslations())
    private val healthProviderMap = providerMap.filter { (providerId, provider) ->
        if (providerId == OnlineProviderIds.UNIFIED) return@filter false
        val descriptor = provider.descriptor
        descriptor.capabilities.catalog ||
            (descriptor.capabilities.search &&
                descriptor.healthProbeQuery.isNotBlank() &&
                descriptor.healthProbeQuery.length >= descriptor.minimumSearchLength.coerceAtLeast(1))
    }
    init {
        healthTracker.register(healthProviderMap.keys)
    }

    val providers: List<OnlineProviderDescriptor> = providers.map(OnlineProvider::descriptor)
    val healthProviders: List<OnlineProviderDescriptor> = healthProviderMap.values.map(OnlineProvider::descriptor)
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()
    val progress: StateFlow<Map<String, OnlineWatchProgress>> = progressStore.progress
    val libraryEntries: StateFlow<Map<String, OnlineLibraryEntry>> = libraryStore.entries
    val accountStates: StateFlow<Map<String, ProviderAccountState>> = _accountStates.asStateFlow()
    val preferredTranslations: StateFlow<Map<String, String>> = _preferredTranslations.asStateFlow()
    val healthStates: StateFlow<Map<String, ProviderHealthState>> = healthTracker.states
    val endpointStates: StateFlow<Map<String, ProviderEndpointState>>? = endpointRegistry?.states

    fun selectProvider(providerId: String) {
        require(providerMap.containsKey(providerId)) { "Unknown online provider: $providerId" }
        preferences.edit { putString(ACTIVE_PROVIDER_KEY, providerId) }
        _activeProviderId.value = providerId
    }

    fun descriptor(providerId: String): OnlineProviderDescriptor = provider(providerId).descriptor

    suspend fun getCatalog(
        providerId: String = activeProviderId.value,
        page: Int,
        limit: Int = 24,
        search: String = "",
    ): OnlineCatalogPage {
        ensureProviderEnabled(providerId)
        val target = provider(providerId)
        target.descriptor.requireCatalogCapability(search)
        val key = CatalogRequestKey(providerId, page, limit, search.trim())
        return catalogRequests.getOrLoad(key) {
            if (providerId == OnlineProviderIds.UNIFIED) {
                target.getCatalog(page, limit, search)
            } else {
                healthTracker.track(providerId, ProviderOperation.CATALOG, target.descriptor.name) {
                    target.getCatalog(page, limit, search)
                }
            }
        }
    }

    suspend fun getRelease(providerId: String, releaseId: String): OnlineReleaseDetails {
        ensureProviderEnabled(providerId)
        val target = provider(providerId)
        target.descriptor.requireReleaseCapability()
        return releaseRequests.getOrLoad(ReleaseRequestKey(providerId, releaseId)) {
            if (providerId == OnlineProviderIds.UNIFIED) {
                target.getRelease(releaseId)
            } else {
                healthTracker.track(providerId, ProviderOperation.RELEASE, target.descriptor.name) {
                    target.getRelease(releaseId)
                }
            }
        }
    }

    suspend fun resolveStreams(
        providerId: String,
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> {
        ensureProviderEnabled(providerId)
        val target = provider(providerId)
        target.descriptor.requireStreamCapability()
        val key = StreamRequestKey(providerId, releaseId, episode.id)
        return streamRequests.getOrLoad(key) {
            if (providerId == OnlineProviderIds.UNIFIED) {
                target.resolveStreams(releaseId, episode)
            } else {
                healthTracker.track(providerId, ProviderOperation.STREAM, target.descriptor.name) {
                    target.resolveStreams(releaseId, episode)
                }
            }
        }
    }

    fun progressFor(providerId: String): Map<String, OnlineWatchProgress> =
        progressStore.forProvider(providerId)

    fun episodeProgress(providerId: String, episodeId: String): OnlineWatchProgress =
        progressStore.get(providerId, episodeId)

    fun saveProgress(
        providerId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): OnlineWatchProgress = progressStore.save(providerId, episodeId, positionMs, durationMs, ended)

    fun recordPlayback(
        release: OnlineReleaseDetails,
        episode: OnlineEpisode,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
    ): OnlineWatchProgress {
        val value = progressStore.save(
            providerId = release.providerId,
            episodeId = episode.id,
            positionMs = positionMs,
            durationMs = durationMs,
            ended = ended,
        )
        libraryStore.recordPlayback(
            release = release,
            episode = episode,
            positionMs = value.positionMs,
            durationMs = value.durationMs,
            completed = value.isCompleted,
        )
        return value
    }

    fun markReleaseOpened(release: OnlineReleaseDetails) = libraryStore.markOpened(release)

    fun setFavorite(release: OnlineReleaseDetails, favorite: Boolean) =
        libraryStore.setFavorite(release, favorite)

    fun libraryEntry(providerId: String, releaseId: String): OnlineLibraryEntry? =
        libraryStore.get(providerId, releaseId)

    fun clearOnlineHistory() = libraryStore.clearHistory()

    fun clearOnlineFavorites() = libraryStore.clearFavorites()

    fun snapshotLibraryEntries(): List<OnlineLibraryEntry> = libraryStore.snapshot()

    fun snapshotOnlineProgress(): Map<String, OnlineWatchProgress> = progressStore.snapshot()

    fun restoreOnlineState(
        entries: List<OnlineLibraryEntry>,
        progress: Map<String, OnlineWatchProgress>,
    ) {
        libraryStore.restore(entries)
        progressStore.restore(progress)
    }

    fun preferredTranslation(providerId: String, releaseId: String): String? =
        _preferredTranslations.value[releasePreferenceKey(providerId, releaseId)]

    fun preferredQuality(providerId: String, releaseId: String): Int? =
        preferences.getInt(PREFERRED_QUALITY_PREFIX + releasePreferenceKey(providerId, releaseId), -1)
            .takeIf { it > 0 }

    fun setPreferredQuality(providerId: String, releaseId: String, quality: Int?) {
        val key = PREFERRED_QUALITY_PREFIX + releasePreferenceKey(providerId, releaseId)
        preferences.edit {
            if (quality == null || quality <= 0) remove(key) else putInt(key, quality)
        }
    }

    fun setPreferredTranslation(providerId: String, releaseId: String, translationKey: String?) {
        val releaseKey = releasePreferenceKey(providerId, releaseId)
        preferences.edit {
            if (translationKey.isNullOrBlank()) {
                remove(PREFERRED_TRANSLATION_PREFIX + releaseKey)
            } else {
                putString(PREFERRED_TRANSLATION_PREFIX + releaseKey, translationKey)
            }
        }
        _preferredTranslations.value = _preferredTranslations.value.toMutableMap().apply {
            if (translationKey.isNullOrBlank()) remove(releaseKey) else put(releaseKey, translationKey)
        }
    }

    fun clearProgress() = progressStore.clear()

    suspend fun checkProvider(providerId: String): ProviderHealthState {
        val target = healthProviderMap[providerId]
            ?: throw OnlineSourceException("Источник '$providerId' нельзя проверить")
        val accountState = _accountStates.value[providerId]
        if (target.descriptor.authMode == ProviderAuthMode.REQUIRED_TOKEN && accountState?.isSignedIn != true) {
            healthTracker.markNeedsConfiguration(providerId, "Нужен API-токен")
            return healthTracker.states.value.getValue(providerId)
        }

        val probeQuery = target.descriptor.healthProbeQuery
        runCatching { target.descriptor.requireCatalogCapability(probeQuery) }
            .onFailure { error ->
                healthTracker.markNeedsConfiguration(providerId, error.message ?: "Нет безопасного health-check")
                return healthTracker.states.value.getValue(providerId)
            }

        healthTracker.markChecking(providerId)
        runCatchingCancellable {
            healthTracker.track(
                providerId = providerId,
                operation = ProviderOperation.HEALTH_CHECK,
                sourceName = target.descriptor.name,
                bypassCircuitBreaker = true,
            ) {
                target.getCatalog(
                    page = 1,
                    limit = 1,
                    search = probeQuery,
                )
            }
        }
        return healthTracker.states.value.getValue(providerId)
    }

    suspend fun checkAllProviders(): Map<String, ProviderHealthState> = supervisorScope {
        healthProviderMap.keys.map { providerId ->
            async { providerId to checkProvider(providerId) }
        }.awaitAll().toMap()
    }

    suspend fun signIn(providerId: String, login: String, password: String): ProviderLoginResult {
        val provider = provider(providerId) as? AccountOnlineProvider
            ?: throw OnlineSourceException("Источник не поддерживает вход по логину и паролю")
        return provider.signIn(login, password).also {
            refreshAccountStates()
            resetHealth(providerId)
        }
    }

    fun setToken(providerId: String, token: String) {
        val provider = provider(providerId) as? TokenOnlineProvider
            ?: throw OnlineSourceException("Источник не поддерживает ручной токен")
        provider.setToken(token)
        refreshAccountStates()
        resetHealth(providerId)
    }

    fun signOut(providerId: String) {
        when (val provider = provider(providerId)) {
            is AccountOnlineProvider -> provider.signOut()
            is TokenOnlineProvider -> provider.signOut()
            else -> return
        }
        refreshAccountStates()
        resetHealth(providerId)
    }

    private fun ensureProviderEnabled(providerId: String) {
        if (providerId == OnlineProviderIds.UNIFIED) return
        if (endpointRegistry?.isEnabled(providerId) == false) {
            throw OnlineSourceException("Источник временно отключён удалённой конфигурацией")
        }
    }

    private fun provider(id: String): OnlineProvider = providerMap[id]
        ?: throw OnlineSourceException("Источник '$id' не подключён")

    private fun refreshAccountStates() {
        _accountStates.value = readAccountStates()
    }

    private fun resetHealth(providerId: String) {
        (providerMap[OnlineProviderIds.UNIFIED] as? UnifiedOnlineProvider)?.clearCatalogCache()
        if (!healthProviderMap.containsKey(providerId)) return
        healthTracker.reset(providerId)
    }

    private fun readAccountStates(): Map<String, ProviderAccountState> = providerMap.values
        .mapNotNull { provider ->
            when (provider) {
                is AccountOnlineProvider -> provider.accountState()
                is TokenOnlineProvider -> provider.accountState()
                else -> null
            }
        }
        .associateBy(ProviderAccountState::providerId)

    private fun readPreferredTranslations(): Map<String, String> = preferences.all
        .asSequence()
        .filter { (key, value) ->
            key.startsWith(PREFERRED_TRANSLATION_PREFIX) && value is String && value.isNotBlank()
        }
        .associate { (key, value) ->
            key.removePrefix(PREFERRED_TRANSLATION_PREFIX) to value as String
        }

    private data class CatalogRequestKey(
        val providerId: String,
        val page: Int,
        val limit: Int,
        val search: String,
    )

    private data class ReleaseRequestKey(val providerId: String, val releaseId: String)
    private data class StreamRequestKey(val providerId: String, val releaseId: String, val episodeId: String)

    private companion object {
        const val PREFERENCES_NAME = "online_settings"
        const val ACTIVE_PROVIDER_KEY = "active_provider"
        const val PREFERRED_TRANSLATION_PREFIX = "preferred_translation."
        const val PREFERRED_QUALITY_PREFIX = "preferred_quality."

        fun releasePreferenceKey(providerId: String, releaseId: String) = "$providerId|$releaseId"
    }
}
