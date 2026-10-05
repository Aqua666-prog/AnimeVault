package com.sergey.animevault.data.metadata

import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.onlineHeaders
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Conservative public-client limiter for Tenrai with headroom below public limits. */
class TenraiRateLimiter(
    private val maxPerSecond: Int = 3,
    private val maxPerMinute: Int = 60,
    private val minSpacingMs: Long = 300L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val secondWindow = ArrayDeque<Long>()
    private val minuteWindow = ArrayDeque<Long>()
    private var lastRequestAtMs: Long? = null

    init {
        require(maxPerSecond > 0)
        require(maxPerMinute > 0)
        require(minSpacingMs >= 0L)
    }

    suspend fun acquire() {
        mutex.lock()
        try {
            while (true) {
                val now = nowMs()
                prune(secondWindow, now, SECOND_MS)
                prune(minuteWindow, now, MINUTE_MS)

                val spacingWait = lastRequestAtMs
                    ?.let { last -> (last + minSpacingMs - now).coerceAtLeast(0L) }
                    ?: 0L
                val secondWait = if (secondWindow.size >= maxPerSecond) {
                    (secondWindow.peekFirst() + SECOND_MS + 1L - now).coerceAtLeast(0L)
                } else {
                    0L
                }
                val minuteWait = if (minuteWindow.size >= maxPerMinute) {
                    (minuteWindow.peekFirst() + MINUTE_MS + 1L - now).coerceAtLeast(0L)
                } else {
                    0L
                }
                val waitMs = maxOf(spacingWait, secondWait, minuteWait)
                if (waitMs <= 0L) {
                    secondWindow.addLast(now)
                    minuteWindow.addLast(now)
                    lastRequestAtMs = now
                    return
                }
                sleeper(waitMs)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun prune(queue: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (queue.isNotEmpty() && now - queue.peekFirst() >= windowMs) {
            queue.removeFirst()
        }
    }

    private companion object {
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60_000L
    }
}

data class TenraiRetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMs: Long = 400L,
    val maxBackoffMs: Long = 2_500L,
    val maxRetryAfterMs: Long = 15_000L,
) {
    init {
        require(maxAttempts >= 1)
        require(initialBackoffMs >= 0L)
        require(maxBackoffMs >= initialBackoffMs)
        require(maxRetryAfterMs >= 0L)
    }
}

enum class TenraiHealthState {
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    UNAVAILABLE,
}

data class TenraiHealthSnapshot(
    val state: TenraiHealthState = TenraiHealthState.HEALTHY,
    val consecutiveFailures: Int = 0,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val retryAfterUntilMs: Long? = null,
    val lastHttpCode: Int? = null,
    val servingStaleCache: Boolean = false,
    val lastStaleHitAtMs: Long? = null,
)

enum class TenraiResponseOrigin {
    NETWORK,
    STALE_CACHE,
}

data class TenraiTextResponse(
    val body: String,
    val origin: TenraiResponseOrigin,
    val cachedAtMs: Long? = null,
)

open class TenraiException(message: String, cause: Throwable? = null) : IOException(message, cause)

class TenraiHttpException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
) : TenraiException(message)

class TenraiProtocolException(message: String, cause: Throwable? = null) : TenraiException(message, cause)

class TenraiTransportException(message: String, cause: Throwable) : TenraiException(message, cause)

/**
 * Resilient Tenrai HTTP client used by all metadata repositories.
 *
 * AnimeVault follows Tenrai.Net's conservative public contract: 60 requests/minute,
 * 3 requests/second and at least 300 ms spacing. A single shared client keeps the
 * limiter global across metadata, extras and discovery requests.
 */
class TenraiClient(
    baseClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = TENRAI_BASE_URL,
    private val retryPolicy: TenraiRetryPolicy = TenraiRetryPolicy(),
    private val limiter: TenraiRateLimiter = TenraiRateLimiter(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val responseCache: TenraiResponseCache? = null,
    private val staleMaxAgeMs: Long = DEFAULT_STALE_MAX_AGE_MS,
) {
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val healthLock = Any()

    @Volatile
    private var health = TenraiHealthSnapshot()

    init {
        require(staleMaxAgeMs >= 0L)
    }

    fun healthSnapshot(): TenraiHealthSnapshot = health

    suspend fun get(
        path: String,
        queryParameters: Map<String, String?> = emptyMap(),
    ): String = getResult(path, queryParameters).body

    suspend fun getResult(
        path: String,
        queryParameters: Map<String, String?> = emptyMap(),
    ): TenraiTextResponse {
        val normalizedPath = normalizePath(path)
        val normalizedQuery = queryParameters.toSortedMap()
        val url = baseUrl.newBuilder()
            .addPathSegments(normalizedPath)
            .apply {
                normalizedQuery.forEach { (name, value) ->
                    require(name.isNotBlank()) { "Tenrai query parameter name is blank" }
                    addQueryParameter(name, value)
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; Tenrai"))
            .header("Accept", "application/json")
            .build()
        val cacheKey = responseCacheKey(normalizedPath, normalizedQuery)

        var attempt = 1
        while (true) {
            limiter.acquire()

            val payload = try {
                execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                val willRetry = attempt < retryPolicy.maxAttempts
                recordTransportFailure(finalFailure = !willRetry)
                if (!willRetry) {
                    cachedFallback(cacheKey)?.let { return it }
                    throw TenraiTransportException("Не удалось подключиться к Tenrai", error)
                }
                sleeper(exponentialBackoff(attempt))
                attempt += 1
                continue
            }

            val retryAfterMs = parseRetryAfterMillis(payload.retryAfter, nowMs())
            if (payload.code in 200..299) {
                if (payload.body.isBlank()) {
                    recordProtocolFailure()
                    cachedFallback(cacheKey)?.let { return it }
                    throw TenraiProtocolException("Tenrai вернул пустой ответ для /$normalizedPath")
                }
                writeCacheSafely(cacheKey, payload.body)
                recordSuccess(payload.code)
                return TenraiTextResponse(
                    body = payload.body,
                    origin = TenraiResponseOrigin.NETWORK,
                )
            }

            val exception = TenraiHttpException(
                code = payload.code,
                retryAfterMs = retryAfterMs,
                message = buildHttpErrorMessage(payload, normalizedPath),
            )
            val delayMs = retryDelayFor(payload.code, retryAfterMs, attempt)
            val willRetry = delayMs != null && attempt < retryPolicy.maxAttempts
            recordHttpFailure(payload.code, retryAfterMs, finalFailure = !willRetry)
            if (!willRetry) {
                if (payload.code == 429 || payload.code in RETRYABLE_HTTP_CODES) {
                    cachedFallback(cacheKey)?.let { return it }
                }
                throw exception
            }

            sleeper(delayMs!!)
            attempt += 1
        }
    }

    private suspend fun cachedFallback(cacheKey: String): TenraiTextResponse? {
        val cache = responseCache ?: return null
        val cached = try {
            cache.get(cacheKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return null
        val ageMs = (nowMs() - cached.storedAtMs).coerceAtLeast(0L)
        if (ageMs > staleMaxAgeMs) return null
        recordStaleHit()
        return TenraiTextResponse(
            body = cached.body,
            origin = TenraiResponseOrigin.STALE_CACHE,
            cachedAtMs = cached.storedAtMs,
        )
    }

    private suspend fun writeCacheSafely(cacheKey: String, body: String) {
        val cache = responseCache ?: return
        try {
            cache.put(cacheKey, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Cache failures must never break fresh network metadata.
        }
    }

    private fun responseCacheKey(path: String, query: Map<String, String?>): String = buildString {
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(
                query.entries.joinToString("&") { (name, value) ->
                    name + "=" + (value ?: "<flag>")
                },
            )
        }
    }

    private suspend fun execute(request: Request): ResponsePayload =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val payload = ResponsePayload(
                                code = it.code,
                                body = it.body?.string().orEmpty(),
                                retryAfter = it.header("Retry-After"),
                            )
                            if (continuation.isActive) continuation.resume(payload)
                        }
                    }
                },
            )
        }

    private fun retryDelayFor(code: Int, retryAfterMs: Long?, attempt: Int): Long? = when {
        code == 429 -> retryAfterMs
            ?.takeIf { it <= retryPolicy.maxRetryAfterMs }
            ?: if (retryAfterMs == null) exponentialBackoff(attempt) else null
        code in RETRYABLE_HTTP_CODES -> exponentialBackoff(attempt)
        else -> null
    }

    private fun exponentialBackoff(attempt: Int): Long {
        var delayMs = retryPolicy.initialBackoffMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2L).coerceAtMost(retryPolicy.maxBackoffMs)
        }
        return delayMs.coerceAtMost(retryPolicy.maxBackoffMs)
    }

    private fun buildHttpErrorMessage(payload: ResponsePayload, path: String): String {
        val compactBody = payload.body
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(ERROR_BODY_LIMIT)
        return buildString {
            append("Tenrai вернул ошибку ")
            append(payload.code)
            append(" для /")
            append(path)
            if (compactBody.isNotBlank()) {
                append(": ")
                append(compactBody)
            }
        }
    }

    private fun recordSuccess(code: Int) = synchronized(healthLock) {
        health = TenraiHealthSnapshot(
            state = TenraiHealthState.HEALTHY,
            consecutiveFailures = 0,
            lastSuccessAtMs = nowMs(),
            lastFailureAtMs = health.lastFailureAtMs,
            retryAfterUntilMs = null,
            lastHttpCode = code,
            servingStaleCache = false,
        )
    }

    private fun recordHttpFailure(code: Int, retryAfterMs: Long?, finalFailure: Boolean) = synchronized(healthLock) {
        if (code in 400..499 && code != 429) return@synchronized
        val now = nowMs()
        val failures = health.consecutiveFailures + 1
        val state = when {
            code == 429 -> TenraiHealthState.RATE_LIMITED
            finalFailure -> TenraiHealthState.UNAVAILABLE
            else -> TenraiHealthState.DEGRADED
        }
        health = health.copy(
            state = state,
            consecutiveFailures = failures,
            lastFailureAtMs = now,
            retryAfterUntilMs = retryAfterMs?.let(now::plus),
            lastHttpCode = code,
        )
    }

    private fun recordTransportFailure(finalFailure: Boolean) = synchronized(healthLock) {
        health = health.copy(
            state = if (finalFailure) TenraiHealthState.UNAVAILABLE else TenraiHealthState.DEGRADED,
            consecutiveFailures = health.consecutiveFailures + 1,
            lastFailureAtMs = nowMs(),
            lastHttpCode = null,
        )
    }

    private fun recordStaleHit() = synchronized(healthLock) {
        health = health.copy(
            state = TenraiHealthState.DEGRADED,
            servingStaleCache = true,
            lastStaleHitAtMs = nowMs(),
        )
    }

    private fun recordProtocolFailure() = synchronized(healthLock) {
        health = health.copy(
            state = TenraiHealthState.UNAVAILABLE,
            consecutiveFailures = health.consecutiveFailures + 1,
            lastFailureAtMs = nowMs(),
        )
    }

    private fun normalizePath(path: String): String {
        val clean = path.trim().trimStart('/')
        require(clean.isNotBlank()) { "Tenrai path is blank" }
        require(!clean.contains("..")) { "Tenrai path must not contain '..'" }
        require(!clean.contains("://")) { "Tenrai path must be relative" }
        return clean
    }

    private data class ResponsePayload(
        val code: Int,
        val body: String,
        val retryAfter: String?,
    )

    companion object {
        val TENRAI_BASE_URL: HttpUrl = "https://api.tenrai.org/v1/".toHttpUrl()
        const val DEFAULT_STALE_MAX_AGE_MS = 7L * 24L * 60L * 60_000L
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val READ_TIMEOUT_SECONDS = 15L
        private const val CALL_TIMEOUT_SECONDS = 20L
        private const val ERROR_BODY_LIMIT = 220
        private val RETRYABLE_HTTP_CODES = setOf(502, 503, 504)
    }
}

internal fun parseRetryAfterMillis(
    value: String?,
    nowMs: Long = System.currentTimeMillis(),
): Long? {
    val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    clean.toLongOrNull()?.let { seconds ->
        return seconds.coerceAtLeast(0L) * 1_000L
    }

    val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("GMT")
    }
    val target = runCatching { parser.parse(clean)?.time }.getOrNull() ?: return null
    return (target - nowMs).coerceAtLeast(0L)
}
