package com.sergey.animevault.data.online

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.sergey.animevault.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope

class OnlineRepository(
    context: Context,
    providers: List<OnlineProvider>,
) {
    private val providerMap = providers.associateBy { it.descriptor.id }
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val progressStore = OnlineProgressStore(context)
    private val libraryStore = OnlineLibraryStore(context)
    private val _activeProviderId = MutableStateFlow(
        preferences.getString(ACTIVE_PROVIDER_KEY, null)
            ?.takeIf(providerMap::containsKey)
            ?: providers.firstOrNull()?.descriptor?.id
            ?: error("At least one online provider is required"),
    )
    private val _accountStates = MutableStateFlow(readAccountStates())
    private val _preferredTranslations = MutableStateFlow(readPreferredTranslations())
    // Jut.su resolves pasted release links and intentionally has no meaningful blank catalog probe.
    // Excluding it avoids reporting a false-positive "healthy" state without touching the network.
    private val healthProviderMap = providerMap.filterKeys { providerId ->
        providerId != OnlineProviderIds.UNIFIED && providerId != OnlineProviderIds.JUT_SU
    }
    private val _healthStates = MutableStateFlow(
        healthProviderMap.keys.associateWith { ProviderHealthState(providerId = it) },
    )

    val providers: List<OnlineProviderDescriptor> = providers.map(OnlineProvider::descriptor)
    val healthProviders: List<OnlineProviderDescriptor> = healthProviderMap.values.map(OnlineProvider::descriptor)
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()
    val progress: StateFlow<Map<String, OnlineWatchProgress>> = progressStore.progress
    val libraryEntries: StateFlow<Map<String, OnlineLibraryEntry>> = libraryStore.entries
    val accountStates: StateFlow<Map<String, ProviderAccountState>> = _accountStates.asStateFlow()
    val preferredTranslations: StateFlow<Map<String, String>> = _preferredTranslations.asStateFlow()
    val healthStates: StateFlow<Map<String, ProviderHealthState>> = _healthStates.asStateFlow()

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
    ): OnlineCatalogPage = provider(providerId).getCatalog(page, limit, search)

    suspend fun getRelease(providerId: String, releaseId: String): OnlineReleaseDetails =
        provider(providerId).getRelease(releaseId)

    suspend fun resolveStreams(
        providerId: String,
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> = provider(providerId).resolveStreams(releaseId, episode)

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
        val provider = healthProviderMap[providerId]
            ?: throw OnlineSourceException("Источник '$providerId' нельзя проверить")
        val accountState = _accountStates.value[providerId]
        if (provider.descriptor.authMode == ProviderAuthMode.REQUIRED_TOKEN && accountState?.isSignedIn != true) {
            return ProviderHealthState(
                providerId = providerId,
                status = ProviderHealthStatus.NEEDS_CONFIGURATION,
                message = "Нужен API-токен",
                checkedAt = System.currentTimeMillis(),
            ).also(::publishHealth)
        }

        publishHealth(
            ProviderHealthState(
                providerId = providerId,
                status = ProviderHealthStatus.CHECKING,
                message = "Проверяем соединение",
            ),
        )
        val startedAt = SystemClock.elapsedRealtime()
        return runCatchingCancellable {
            provider.getCatalog(
                page = 1,
                limit = 1,
                search = provider.descriptor.healthProbeQuery,
            )
            val latency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            ProviderHealthState(
                providerId = providerId,
                status = ProviderHealthStatus.AVAILABLE,
                latencyMs = latency,
                message = "Источник отвечает",
                checkedAt = System.currentTimeMillis(),
            )
        }.getOrElse { error ->
            ProviderHealthState(
                providerId = providerId,
                status = ProviderHealthStatus.UNAVAILABLE,
                latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                message = error.message?.takeIf(String::isNotBlank) ?: "Источник не ответил",
                checkedAt = System.currentTimeMillis(),
            )
        }.also(::publishHealth)
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

    private fun provider(id: String): OnlineProvider = providerMap[id]
        ?: throw OnlineSourceException("Источник '$id' не подключён")

    private fun refreshAccountStates() {
        _accountStates.value = readAccountStates()
    }

    private fun publishHealth(state: ProviderHealthState) {
        _healthStates.update { current -> current + (state.providerId to state) }
    }

    private fun resetHealth(providerId: String) {
        (providerMap[OnlineProviderIds.UNIFIED] as? UnifiedOnlineProvider)?.clearCatalogCache()
        if (!healthProviderMap.containsKey(providerId)) return
        publishHealth(ProviderHealthState(providerId = providerId))
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

    private companion object {
        const val PREFERENCES_NAME = "online_settings"
        const val ACTIVE_PROVIDER_KEY = "active_provider"
        const val PREFERRED_TRANSLATION_PREFIX = "preferred_translation."
        const val PREFERRED_QUALITY_PREFIX = "preferred_quality."

        fun releasePreferenceKey(providerId: String, releaseId: String) = "$providerId|$releaseId"
    }
}
