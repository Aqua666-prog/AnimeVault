package com.sergey.animevault.data.online

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Runtime provider endpoint configuration.
 *
 * The registry keeps the last valid remote configuration in SharedPreferences, so a provider can
 * move domains without forcing an APK release. Requests are still restricted to HTTPS origins and
 * only the provider's own host is rewritten; CDN/player URLs returned by a provider are left alone.
 */
data class ProviderEndpointConfig(
    @SerializedName("id") val providerId: String,
    val enabled: Boolean = true,
    val endpoints: List<String> = emptyList(),
    val priority: Int = 0,
)

data class ProviderRemoteConfig(
    val schemaVersion: Int = 1,
    val providers: List<ProviderEndpointConfig> = emptyList(),
)

data class ProviderEndpointState(
    val providerId: String,
    val enabled: Boolean,
    val endpoints: List<String>,
    val activeIndex: Int = 0,
    val priority: Int = 0,
) {
    val activeEndpoint: String?
        get() = endpoints.getOrNull(activeIndex.coerceIn(0, (endpoints.size - 1).coerceAtLeast(0)))

    fun orderedEndpoints(): List<String> {
        if (endpoints.isEmpty()) return emptyList()
        val index = activeIndex.coerceIn(0, endpoints.lastIndex)
        return endpoints.drop(index) + endpoints.take(index)
    }
}

class ProviderEndpointRegistry(
    context: Context,
    private val gson: Gson = Gson(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val defaults = defaultProviderEndpoints()
    private val _states = MutableStateFlow(buildInitialStates())
    val states: StateFlow<Map<String, ProviderEndpointState>> = _states.asStateFlow()

    fun state(providerId: String): ProviderEndpointState = _states.value[providerId]
        ?: ProviderEndpointState(providerId, enabled = true, endpoints = emptyList())

    fun isEnabled(providerId: String): Boolean = state(providerId).enabled

    fun originalHosts(providerId: String): Set<String> = defaults[providerId]
        .orEmpty()
        .mapNotNull { it.toHttpUrlOrNull()?.host }
        .toSet()

    fun markEndpointSuccess(providerId: String, endpoint: String) {
        val normalized = normalizeEndpoint(endpoint) ?: return
        _states.update { current ->
            val state = current[providerId] ?: return@update current
            val index = state.endpoints.indexOf(normalized)
            if (index < 0 || index == state.activeIndex) current
            else current + (providerId to state.copy(activeIndex = index))
        }
    }

    fun rotate(providerId: String) {
        _states.update { current ->
            val state = current[providerId] ?: return@update current
            if (state.endpoints.size <= 1) return@update current
            current + (providerId to state.copy(activeIndex = (state.activeIndex + 1) % state.endpoints.size))
        }
    }

    fun applyRemoteConfig(config: ProviderRemoteConfig): Boolean {
        if (!validateRemoteConfigShape(config, defaults.keys)) return false
        val currentStates = _states.value
        val normalized = config.providers
            .asSequence()
            .mapNotNull { incoming ->
                val id = incoming.providerId.trim()
                val fallback = defaults[id] ?: return@mapNotNull null
                val endpoints = incoming.endpoints
                    .take(MAX_ENDPOINTS_PER_PROVIDER)
                    .mapNotNull(::normalizeEndpoint)
                    .distinct()
                    .ifEmpty { fallback }
                val previousEndpoint = currentStates[id]?.activeEndpoint
                val activeIndex = previousEndpoint
                    ?.let(endpoints::indexOf)
                    ?.takeIf { it >= 0 }
                    ?: 0
                id to ProviderEndpointState(
                    providerId = id,
                    enabled = incoming.enabled,
                    endpoints = endpoints.distinct(),
                    activeIndex = activeIndex,
                    priority = incoming.priority,
                )
            }
            .toMap()
        if (normalized.isEmpty() || normalized.values.none(ProviderEndpointState::enabled)) return false

        _states.update { current ->
            buildMap {
                putAll(current)
                normalized.forEach { (id, state) -> put(id, state) }
            }
        }
        preferences.edit {
            putString(PERSISTED_REMOTE_CONFIG, gson.toJson(config.copy(
                providers = normalized.values.map {
                    ProviderEndpointConfig(it.providerId, it.enabled, it.endpoints, it.priority)
                },
            )))
        }
        return true
    }

    fun clientFor(
        providerId: String,
        baseBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): OkHttpClient = baseBuilder
        .addInterceptor(ProviderEndpointInterceptor(providerId, this))
        .build()

    private fun buildInitialStates(): Map<String, ProviderEndpointState> {
        val base = defaults.mapValues { (id, endpoints) ->
            ProviderEndpointState(id, enabled = true, endpoints = endpoints)
        }.toMutableMap()
        val persisted = preferences.getString(PERSISTED_REMOTE_CONFIG, null)
            ?.let { runCatching { gson.fromJson(it, ProviderRemoteConfig::class.java) }.getOrNull() }
        if (persisted?.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            persisted.providers.forEach { config ->
                val id = config.providerId.trim()
                val endpoints = config.endpoints.mapNotNull(::normalizeEndpoint).distinct()
                val fallback = defaults[id].orEmpty()
                if (id.isNotBlank()) {
                    base[id] = ProviderEndpointState(
                        providerId = id,
                        enabled = config.enabled,
                        endpoints = (endpoints.ifEmpty { fallback }).distinct(),
                        priority = config.priority,
                    )
                }
            }
        }
        return base
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_ENDPOINTS_PER_PROVIDER = 8
        private const val PREFERENCES_NAME = "provider_endpoint_registry"
        private const val PERSISTED_REMOTE_CONFIG = "remote_config"

        internal fun normalizeEndpoint(value: String): String? {
            val url = value.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            if (url.encodedPath != "/") return null
            return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}

internal fun validateRemoteConfigShape(
    config: ProviderRemoteConfig,
    knownProviderIds: Set<String>,
): Boolean {
    if (config.schemaVersion != ProviderEndpointRegistry.SUPPORTED_SCHEMA_VERSION) return false
    if (config.providers.isEmpty()) return false
    val ids = config.providers.map { it.providerId.trim() }
    if (ids.any(String::isBlank) || ids.size != ids.distinct().size) return false
    val known = config.providers.filter { it.providerId.trim() in knownProviderIds }
    if (known.isEmpty() || known.none(ProviderEndpointConfig::enabled)) return false
    if (known.any { it.endpoints.size > ProviderEndpointRegistry.MAX_ENDPOINTS_PER_PROVIDER }) return false
    return true
}

internal class ProviderEndpointInterceptor(
    private val providerId: String,
    private val registry: ProviderEndpointRegistry,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!registry.isEnabled(providerId)) {
            throw OnlineSourceException("Источник временно отключён конфигурацией AnimeVault")
        }
        val request = chain.request()
        val originalHosts = registry.originalHosts(providerId)
        if (request.url.host !in originalHosts) return chain.proceed(request)

        val candidates = registry.state(providerId).orderedEndpoints()
        if (candidates.isEmpty()) return chain.proceed(request)

        val retryable = request.method == "GET" || request.method == "HEAD"
        var lastError: IOException? = null
        candidates.forEachIndexed { index, endpoint ->
            val endpointUrl = endpoint.toHttpUrlOrNull() ?: return@forEachIndexed
            val rewritten = request.rewriteOrigin(endpointUrl)
            try {
                val response = chain.proceed(rewritten)
                val serverFailure = response.code in 500..599
                if (!serverFailure || !retryable || index == candidates.lastIndex) {
                    if (!serverFailure) registry.markEndpointSuccess(providerId, endpoint)
                    return response
                }
                response.close()
                registry.rotate(providerId)
            } catch (error: IOException) {
                lastError = error
                if (!retryable || index == candidates.lastIndex) throw error
                registry.rotate(providerId)
            }
        }
        throw lastError ?: IOException("No endpoint available for $providerId")
    }
}

private fun Request.rewriteOrigin(endpoint: HttpUrl): Request = newBuilder()
    .url(
        url.newBuilder()
            .scheme(endpoint.scheme)
            .host(endpoint.host)
            .port(endpoint.port)
            .build(),
    )
    .build()

class ProviderRemoteConfigRepository(
    private val registry: ProviderEndpointRegistry,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    suspend fun refresh(url: String = DEFAULT_REMOTE_CONFIG_URL): Boolean = withContext(Dispatchers.IO) {
        val configUrl = url.toHttpUrlOrNull()?.takeIf(HttpUrl::isHttps) ?: return@withContext false
        val request = Request.Builder()
            .url(configUrl)
            .header("Accept", "application/json")
            .header("User-Agent", animeVaultUserAgent())
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val body = response.body?.string().orEmpty()
                if (body.isBlank() || body.length > MAX_CONFIG_CHARS) return@runCatching false
                val config = gson.fromJson(body, ProviderRemoteConfig::class.java) ?: return@runCatching false
                registry.applyRemoteConfig(config)
            }
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/Aqua666-prog/AnimeVault/main/provider-config.json"
        private const val MAX_CONFIG_CHARS = 256 * 1024
    }
}

private fun defaultProviderEndpoints(): Map<String, List<String>> = mapOf(
    OnlineProviderIds.ANI_LIBERTY to listOf("https://aniliberty.top"),
    OnlineProviderIds.KODIK to listOf("https://kodik-api.com"),
    OnlineProviderIds.ANIME_LIB to listOf("https://api.cdnlibs.org"),
    OnlineProviderIds.ANIME_VOST to listOf("https://api.animevost.org"),
    OnlineProviderIds.ANIMEDIA to listOf("https://amd.online"),
    OnlineProviderIds.ANIME_ON to listOf("https://animeon.club"),
    OnlineProviderIds.SAMEBAND to listOf("https://sameband.studio"),
    OnlineProviderIds.ANIME_BEST to listOf("https://b1.animebesst.org"),
    OnlineProviderIds.YUMMY to listOf("https://api.yani.tv"),
)
