package com.sergey.animevault.data.playback

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.HttpException

/** Three states shared by local and online playback. */
enum class WatchState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
}

data class PlaybackProgressSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isCompleted: Boolean,
    val lastWatchedAt: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val completedAt: Long? = null,
    val playCount: Int = 0,
) {
    val state: WatchState
        get() = when {
            isCompleted -> WatchState.COMPLETED
            positionMs > 0L -> WatchState.IN_PROGRESS
            else -> WatchState.NOT_STARTED
        }

    val fraction: Float
        get() = when {
            isCompleted -> 1f
            durationMs <= 0L -> 0f
            else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

/** Chooses the safest progress when local and online transports describe the same episode. */
object PlaybackProgressMerger {
    fun choose(
        primary: PlaybackProgressSnapshot,
        secondary: PlaybackProgressSnapshot?,
    ): PlaybackProgressSnapshot {
        secondary ?: return primary
        if (primary.isCompleted != secondary.isCompleted) {
            return if (primary.isCompleted) primary else secondary
        }
        val primaryFraction = primary.fraction
        val secondaryFraction = secondary.fraction
        return when {
            primaryFraction != secondaryFraction -> if (primaryFraction >= secondaryFraction) primary else secondary
            primary.positionMs != secondary.positionMs -> if (primary.positionMs >= secondary.positionMs) primary else secondary
            primary.lastWatchedAt >= secondary.lastWatchedAt -> primary
            else -> secondary
        }
    }
}

/**
 * Single completion policy for offline files and online streams.
 *
 * Completion uses 92% plus a 90-second tail for normal-length episodes.
 * A separate, stricter rule is used when a network player unexpectedly reports STATE_ENDED,
 * so a one-second CDN placeholder cannot mark a full episode as watched.
 */
object PlaybackCompletionPolicy {
    const val COMPLETION_FRACTION = 0.92
    const val COMPLETION_TAIL_MS = 90_000L
    const val MINIMUM_CREDIBLE_DURATION_MS = 60_000L
    const val CREDIBLE_END_TAIL_MS = 30_000L

    fun normalize(
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        watchedAt: Long = System.currentTimeMillis(),
    ): PlaybackProgressSnapshot {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(
            minimumValue = 0L,
            maximumValue = safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        val completed = ended || isPastCompletionThreshold(safePosition, safeDuration)
        return PlaybackProgressSnapshot(
            positionMs = if (completed) 0L else safePosition,
            durationMs = safeDuration,
            isCompleted = completed,
            lastWatchedAt = watchedAt.coerceAtLeast(0L),
            firstPlayedAt = watchedAt.coerceAtLeast(0L),
            completedAt = watchedAt.coerceAtLeast(0L).takeIf { completed },
            playCount = 1,
        )
    }

    fun isPastCompletionThreshold(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val safePosition = positionMs.coerceIn(0L, durationMs)
        val remaining = (durationMs - safePosition).coerceAtLeast(0L)
        return safePosition >= (durationMs * COMPLETION_FRACTION).toLong() ||
            (durationMs >= 10 * 60_000L && remaining <= COMPLETION_TAIL_MS)
    }

    /** Validates a natural player end before treating it as an explicit completion event. */
    fun isCredibleNaturalEnd(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs < MINIMUM_CREDIBLE_DURATION_MS) return false
        val completionWindowMs = minOf(
            CREDIBLE_END_TAIL_MS,
            (durationMs * (1.0 - COMPLETION_FRACTION)).toLong(),
        )
        return positionMs.coerceAtLeast(0L) >= durationMs - completionWindowMs
    }
}

enum class PlaybackFailureKind {
    TIMEOUT,
    DNS,
    CONNECTION,
    TLS,
    AUTH_REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    DECODER,
    UNSUPPORTED_STREAM,
    NETWORK,
    UNKNOWN,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val detail: String? = null,
    val httpCode: Int? = null,
) {
    fun userMessage(sourceName: String): String = when (kind) {
        PlaybackFailureKind.TIMEOUT -> "$sourceName слишком долго не отвечает."
        PlaybackFailureKind.DNS -> "Не удалось найти сервер $sourceName. Проверьте сеть или повторите позже."
        PlaybackFailureKind.CONNECTION -> "Не удалось подключиться к $sourceName."
        PlaybackFailureKind.TLS -> "Не удалось установить защищённое соединение с $sourceName."
        PlaybackFailureKind.AUTH_REQUIRED -> "$sourceName требует авторизацию или новый токен."
        PlaybackFailureKind.FORBIDDEN -> "Доступ к потоку $sourceName запрещён или ссылка устарела."
        PlaybackFailureKind.NOT_FOUND -> "Поток больше не доступен на $sourceName."
        PlaybackFailureKind.RATE_LIMITED -> "$sourceName временно ограничил число запросов."
        PlaybackFailureKind.SERVER -> "$sourceName временно недоступен${httpCode?.let { " (HTTP $it)" }.orEmpty()}."
        PlaybackFailureKind.DECODER -> "Устройство не смогло декодировать этот вариант видео."
        PlaybackFailureKind.UNSUPPORTED_STREAM -> "Этот формат потока не поддерживается устройством."
        PlaybackFailureKind.NETWORK -> "Соединение с $sourceName прервалось."
        PlaybackFailureKind.UNKNOWN -> detail?.takeIf(String::isNotBlank)
            ?: "Не удалось воспроизвести поток $sourceName."
    }
}

object PlaybackFailureClassifier {
    fun classify(error: Throwable): PlaybackFailure {
        val http = error.findHttpException()
        if (http != null) {
            return fromHttpCode(http.code(), error.message)
        }

        val root = error.rootCause()
        return when (root) {
            is SocketTimeoutException -> PlaybackFailure(PlaybackFailureKind.TIMEOUT, root.message)
            is UnknownHostException -> PlaybackFailure(PlaybackFailureKind.DNS, root.message)
            is ConnectException -> PlaybackFailure(PlaybackFailureKind.CONNECTION, root.message)
            is SSLException -> PlaybackFailure(PlaybackFailureKind.TLS, root.message)
            is IOException -> classifyIOException(root)
            else -> classifyByText(root)
        }
    }

    private fun fromHttpCode(code: Int, detail: String?): PlaybackFailure = PlaybackFailure(
        kind = when (code) {
            401 -> PlaybackFailureKind.AUTH_REQUIRED
            403 -> PlaybackFailureKind.FORBIDDEN
            404, 410 -> PlaybackFailureKind.NOT_FOUND
            429 -> PlaybackFailureKind.RATE_LIMITED
            in 500..599 -> PlaybackFailureKind.SERVER
            else -> PlaybackFailureKind.NETWORK
        },
        detail = detail,
        httpCode = code,
    )

    private fun classifyIOException(error: IOException): PlaybackFailure {
        val text = error.describe().lowercase()
        return when {
            "timeout" in text || "timed out" in text -> PlaybackFailure(PlaybackFailureKind.TIMEOUT, error.message)
            "unable to resolve host" in text || "unknownhost" in text -> PlaybackFailure(PlaybackFailureKind.DNS, error.message)
            "ssl" in text || "tls" in text || "certificate" in text -> PlaybackFailure(PlaybackFailureKind.TLS, error.message)
            else -> PlaybackFailure(PlaybackFailureKind.NETWORK, error.message)
        }
    }

    private fun classifyByText(error: Throwable): PlaybackFailure {
        val text = error.describe().lowercase()
        return when {
            "decoder" in text || "mediacodec" in text -> PlaybackFailure(PlaybackFailureKind.DECODER, error.message)
            "unsupported" in text || "unrecognizedinputformat" in text -> {
                PlaybackFailure(PlaybackFailureKind.UNSUPPORTED_STREAM, error.message)
            }
            "timeout" in text || "timed out" in text -> PlaybackFailure(PlaybackFailureKind.TIMEOUT, error.message)
            "403" in text || "forbidden" in text -> PlaybackFailure(PlaybackFailureKind.FORBIDDEN, error.message, 403)
            "404" in text || "not found" in text -> PlaybackFailure(PlaybackFailureKind.NOT_FOUND, error.message, 404)
            else -> PlaybackFailure(PlaybackFailureKind.UNKNOWN, error.message)
        }
    }

    private fun Throwable.findHttpException(): HttpException? {
        var current: Throwable? = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is HttpException) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        val seen = HashSet<Throwable>()
        while (current.cause != null && current.cause !== current && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun Throwable.describe(): String = buildString {
        append(javaClass.simpleName)
        message?.let {
            append(':')
            append(' ')
            append(it)
        }
    }
}
