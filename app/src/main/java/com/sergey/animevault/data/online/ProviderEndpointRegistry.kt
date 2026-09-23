package com.sergey.animevault.data.online

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.Locale
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
 * Schema v2 separates a logical provider from one concrete hostname. Each provider has an APK
 * bundled trust family (for example `animebesst.org`), while the remote config can select any HTTPS
 * endpoint inside that family. This keeps emergency mirror switching useful without allowing a
 * compromised/stale config to redirect provider traffic to an unrelated domain.
 */
data class ProviderEndpointConfig(
    @SerializedName("id") val providerId: String,
    val enabled: Boolean = true,
    val endpoints: List<String> = emptyList(),
    val priority: Int = 0,
    /** Optional narrowing of the APK bundled trust family; it can never widen it. */
    val trustedHostSuffixes: List<String> = emptyList(),
)

data class ProviderRemoteConfig(
    val schemaVersion: Int = ProviderEndpointRegistry.SUPPORTED_SCHEMA_VERSION,
    val configVersion: Long = 0L,
    val issuedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val providers: List<ProviderEndpointConfig> = emptyList(),
)

data class ProviderEndpointState(
    val providerId: String,
    val enabled: Boolean,
    val endpoints: List<String>,
    val activeIndex: Int = 0,
    val priority: Int = 0,
    val trustedHostSuffixes: List<String> = emptyList(),
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
    private val baseClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val defaults = defaultProviderEndpoints()
    private val defaultTrustedSuffixes = defaultProviderTrustedHostSuffixes()
    private val _states = MutableStateFlow(buildInitialStates())
    val states: StateFlow<Map<String, ProviderEndpointState>> = _states.asStateFlow()

    fun state(providerId: String): ProviderEndpointState = _states.value[providerId]
        ?: ProviderEndpointState(providerId, enabled = true, endpoints = emptyList())

    fun isEnabled(providerId: String): Boolean = state(providerId).enabled

    fun originalHosts(providerId: String): Set<String> = defaults[providerId]
        .orEmpty()
        .mapNotNull { it.toHttpUrlOrNull()?.host }
        .toSet()

    fun trustedHostSuffixes(providerId: String): Set<String> = state(providerId).trustedHostSuffixes
        .ifEmpty { defaultTrustedSuffixes[providerId].orEmpty() }
        .mapNotNull(::normalizeHostSuffix)
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
        val now = System.currentTimeMillis()
        val highestVersion = preferences.getLong(HIGHEST_CONFIG_VERSION, 0L)
        if (!validateRemoteConfigShape(config, defaults.keys, now, highestVersion)) return false
        val currentStates = _states.value
        val normalized = config.providers
            .asSequence()
            .mapNotNull { incoming ->
                val id = incoming.providerId.trim()
                val fallback = defaults[id] ?: return@mapNotNull null
                val trusted = trustedSuffixesForRemoteConfig(id, incoming.trustedHostSuffixes)
                val endpoints = incoming.endpoints
                    .take(MAX_ENDPOINTS_PER_PROVIDER)
                    .mapNotNull { normalizeEndpointForProvider(id, it, trusted) }
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
                    priority = incoming.priority.coerceIn(MIN_PROVIDER_PRIORITY, MAX_PROVIDER_PRIORITY),
                    trustedHostSuffixes = trusted.sorted(),
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
            putLong(HIGHEST_CONFIG_VERSION, config.configVersion)
            putString(
                PERSISTED_REMOTE_CONFIG,
                gson.toJson(
                    config.copy(
                        providers = normalized.values.map {
                            ProviderEndpointConfig(
                                providerId = it.providerId,
                                enabled = it.enabled,
                                endpoints = it.endpoints,
                                priority = it.priority,
                                trustedHostSuffixes = it.trustedHostSuffixes,
                            )
                        },
                    ),
                ),
            )
        }
        return true
    }

    fun clientFor(
        providerId: String,
        baseBuilder: OkHttpClient.Builder = baseClient.newBuilder(),
    ): OkHttpClient = baseBuilder
        .addInterceptor(ProviderEndpointInterceptor(providerId, this))
        .build()

    private fun buildInitialStates(): Map<String, ProviderEndpointState> {
        val base = defaults.mapValues { (id, endpoints) ->
            ProviderEndpointState(
                providerId = id,
                enabled = true,
                endpoints = endpoints,
                priority = defaultProviderPriorities()[id] ?: 0,
                trustedHostSuffixes = defaultTrustedSuffixes[id].orEmpty(),
            )
        }.toMutableMap()
        val persisted = preferences.getString(PERSISTED_REMOTE_CONFIG, null)
            ?.let { runCatching { gson.fromJson(it, ProviderRemoteConfig::class.java) }.getOrNull() }
        val persistedVersion = preferences.getLong(HIGHEST_CONFIG_VERSION, 0L)
        if (persisted != null && persisted.configVersion == persistedVersion &&
            validateRemoteConfigShape(persisted, defaults.keys, highestAcceptedVersion = 0L)
        ) {
            persisted.providers.forEach { config ->
                val id = config.providerId.trim()
                val fallback = defaults[id] ?: return@forEach
                val trusted = trustedSuffixesForRemoteConfig(id, config.trustedHostSuffixes)
                val endpoints = config.endpoints
                    .mapNotNull { normalizeEndpointForProvider(id, it, trusted) }
                    .distinct()
                base[id] = ProviderEndpointState(
                    providerId = id,
                    enabled = config.enabled,
                    endpoints = (endpoints.ifEmpty { fallback }).distinct(),
                    priority = config.priority.coerceIn(MIN_PROVIDER_PRIORITY, MAX_PROVIDER_PRIORITY),
                    trustedHostSuffixes = trusted.sorted(),
                )
            }
        }
        return base
    }

    private fun trustedSuffixesForRemoteConfig(providerId: String, requested: List<String>): Set<String> {
        val builtIn = defaultTrustedSuffixes[providerId]
            .orEmpty()
            .mapNotNull(::normalizeHostSuffix)
            .toSet()
        if (builtIn.isEmpty()) return emptySet()
        val narrowed = requested
            .take(MAX_TRUSTED_SUFFIXES_PER_PROVIDER)
            .mapNotNull(::normalizeHostSuffix)
            .filter { requestedSuffix ->
                // The remote config may choose a more specific suffix, but never an unrelated/root-wider one.
                builtIn.any { allowed ->
                    requestedSuffix == allowed || requestedSuffix.endsWith(".$allowed")
                }
            }
            .toSet()
        return narrowed.ifEmpty { builtIn }
    }

    private fun normalizeEndpointForProvider(
        providerId: String,
        value: String,
        trustedSuffixes: Set<String> = defaultTrustedSuffixes[providerId].orEmpty().toSet(),
    ): String? {
        val normalized = normalizeEndpoint(value) ?: return null
        val host = normalized.toHttpUrlOrNull()?.host ?: return null
        if (trustedSuffixes.isEmpty()) return null
        return normalized.takeIf { isTrustedProviderEndpointHost(host, trustedSuffixes) }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 2
        const val MAX_ENDPOINTS_PER_PROVIDER = 8
        const val MAX_TRUSTED_SUFFIXES_PER_PROVIDER = 8
        const val MIN_PROVIDER_PRIORITY = -1000
        const val MAX_PROVIDER_PRIORITY = 1000
        private const val PREFERENCES_NAME = "provider_endpoint_registry"
        private const val PERSISTED_REMOTE_CONFIG = "remote_config"
        private const val HIGHEST_CONFIG_VERSION = "highest_config_version"

        internal fun normalizeEndpoint(value: String): String? {
            val url = value.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            if (url.encodedPath != "/") return null
            return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}

internal fun normalizeHostSuffix(value: String): String? {
    val suffix = value.trim().trim('.').lowercase(Locale.ROOT)
    if (suffix.isBlank() || suffix.length > 253) return null
    if (suffix.contains('/') || suffix.contains(':') || suffix.contains('@')) return null
    val labels = suffix.split('.')
    if (labels.size < 2) return null
    if (labels.any { label ->
            label.isBlank() || label.length > 63 ||
                label.startsWith('-') || label.endsWith('-') ||
                label.any { ch -> !(ch.isLetterOrDigit() || ch == '-') }
        }
    ) return null
    return suffix
}

internal fun isTrustedProviderEndpointHost(host: String, trustedHosts: Set<String>): Boolean {
    val candidate = host.trim().trim('.').lowercase(Locale.ROOT)
    if (candidate.isBlank()) return false
    return trustedHosts.any { rawTrusted ->
        val trusted = normalizeHostSuffix(rawTrusted) ?: return@any false
        candidate == trusted || candidate.endsWith(".$trusted")
    }
}

internal fun validateRemoteConfigShape(
    config: ProviderRemoteConfig,
    knownProviderIds: Set<String>,
    now: Long = System.currentTimeMillis(),
    highestAcceptedVersion: Long = 0L,
): Boolean {
    if (config.schemaVersion != ProviderEndpointRegistry.SUPPORTED_SCHEMA_VERSION) return false
    if (config.configVersion <= highestAcceptedVersion || config.configVersion <= 0L) return false
    if (config.issuedAt <= 0L || config.issuedAt > now + MAX_CONFIG_CLOCK_SKEW_MS) return false
    if (config.expiresAt <= now || config.expiresAt <= config.issuedAt) return false
    if (config.expiresAt - config.issuedAt > MAX_CONFIG_LIFETIME_MS) return false
    if (config.providers.isEmpty()) return false
    val ids = config.providers.map { it.providerId.trim() }
    if (ids.any(String::isBlank) || ids.size != ids.distinct().size) return false
    val known = config.providers.filter { it.providerId.trim() in knownProviderIds }
    if (known.isEmpty() || known.none(ProviderEndpointConfig::enabled)) return false
    if (known.any { it.endpoints.size > ProviderEndpointRegistry.MAX_ENDPOINTS_PER_PROVIDER }) return false
    if (known.any { it.trustedHostSuffixes.size > ProviderEndpointRegistry.MAX_TRUSTED_SUFFIXES_PER_PROVIDER }) return false
    if (known.any { provider -> provider.trustedHostSuffixes.any { normalizeHostSuffix(it) == null } }) return false
    if (known.any { it.priority !in ProviderEndpointRegistry.MIN_PROVIDER_PRIORITY..ProviderEndpointRegistry.MAX_PROVIDER_PRIORITY }) {
        return false
    }
    return true
}

private const val MAX_CONFIG_CLOCK_SKEW_MS = 10 * 60 * 1000L
private const val MAX_CONFIG_LIFETIME_MS = 30L * 24L * 60L * 60L * 1000L

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
    OnlineProviderIds.ANIME_LIB to listOf("https://api.cdnlibs.org", "https://api.lib.social"),
    OnlineProviderIds.ANIME_VOST to listOf("https://api.animevost.org"),
    OnlineProviderIds.JUT_SU to listOf("https://jut.su"),
    OnlineProviderIds.DREAMERSCAST to listOf("https://dreamerscast.com"),
    OnlineProviderIds.ANIMEDIA to listOf("https://amd.online"),
    OnlineProviderIds.ANIME_ON to listOf("https://animeon.club"),
    OnlineProviderIds.SAMEBAND to listOf("https://sameband.studio"),
    OnlineProviderIds.ANIME_BEST to listOf("https://b1.animebesst.org"),
    OnlineProviderIds.YUMMY to listOf("https://api.yani.tv"),
)


private fun defaultProviderPriorities(): Map<String, Int> = mapOf(
    OnlineProviderIds.ANI_LIBERTY to 100,
    OnlineProviderIds.KODIK to 90,
    OnlineProviderIds.ANIME_VOST to 80,
    OnlineProviderIds.ANIME_LIB to 75,
    OnlineProviderIds.DREAMERSCAST to 65,
    OnlineProviderIds.SAMEBAND to 60,
    OnlineProviderIds.YUMMY to 55,
    OnlineProviderIds.ANIMEDIA to 50,
    OnlineProviderIds.ANIME_ON to 45,
    OnlineProviderIds.ANIME_BEST to 40,
    OnlineProviderIds.JUT_SU to 35,
)

/** APK bundled trust roots. A remote config can move inside these families, but not outside them. */
private fun defaultProviderTrustedHostSuffixes(): Map<String, List<String>> = mapOf(
    OnlineProviderIds.ANI_LIBERTY to listOf("aniliberty.top", "anilibria.top"),
    OnlineProviderIds.KODIK to listOf("kodik-api.com"),
    OnlineProviderIds.ANIME_LIB to listOf("cdnlibs.org", "lib.social"),
    OnlineProviderIds.ANIME_VOST to listOf("animevost.org"),
    OnlineProviderIds.JUT_SU to listOf("jut.su"),
    OnlineProviderIds.DREAMERSCAST to listOf("dreamerscast.com"),
    OnlineProviderIds.ANIMEDIA to listOf("amd.online"),
    OnlineProviderIds.ANIME_ON to listOf("animeon.club"),
    OnlineProviderIds.SAMEBAND to listOf("sameband.studio"),
    OnlineProviderIds.ANIME_BEST to listOf("animebesst.org"),
    OnlineProviderIds.YUMMY to listOf("yani.tv"),
)
