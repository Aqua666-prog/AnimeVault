package com.sergey.animevault.data.download

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.roundToLong

internal data class DownloadProgressEstimate(
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
)

/**
 * Sliding/EMA estimator kept outside the Worker so progress math is deterministic and testable.
 */
internal class DownloadProgressEstimator(
    private val smoothing: Double = 0.28,
) {
    private var lastAtMs: Long? = null
    private var lastBytes: Long = 0L
    private var lastCompletedItems: Int = 0
    private var smoothedBytesPerSecond: Double = 0.0
    private var smoothedItemsPerSecond: Double = 0.0

    fun sample(
        bytesDownloaded: Long,
        contentLength: Long,
        completedItems: Int,
        totalItems: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): DownloadProgressEstimate {
        val previousAt = lastAtMs
        if (previousAt != null && nowMs > previousAt) {
            val seconds = (nowMs - previousAt) / 1000.0
            val byteDelta = (bytesDownloaded - lastBytes).coerceAtLeast(0L)
            val itemDelta = (completedItems - lastCompletedItems).coerceAtLeast(0)
            if (byteDelta > 0L) {
                val instant = byteDelta / seconds
                smoothedBytesPerSecond = ema(smoothedBytesPerSecond, instant)
            }
            if (itemDelta > 0) {
                val instant = itemDelta / seconds
                smoothedItemsPerSecond = ema(smoothedItemsPerSecond, instant)
            }
        }
        lastAtMs = nowMs
        lastBytes = bytesDownloaded
        lastCompletedItems = completedItems

        val eta = when {
            contentLength > bytesDownloaded && smoothedBytesPerSecond > 1.0 ->
                ((contentLength - bytesDownloaded) / smoothedBytesPerSecond).roundToLong().coerceAtLeast(0L)
            totalItems > completedItems && smoothedItemsPerSecond > 0.001 ->
                ((totalItems - completedItems) / smoothedItemsPerSecond).roundToLong().coerceAtLeast(0L)
            else -> null
        }
        return DownloadProgressEstimate(
            speedBytesPerSecond = smoothedBytesPerSecond.roundToLong().coerceAtLeast(0L),
            etaSeconds = eta,
        )
    }

    private fun ema(previous: Double, current: Double): Double =
        if (previous <= 0.0) current else smoothing * current + (1.0 - smoothing) * previous
}

internal data class DownloadFailure(
    val kind: DownloadFailureKind,
    val retryable: Boolean,
    val shouldRefreshSource: Boolean,
    val message: String,
)

internal object DownloadFailureClassifier {
    fun classify(error: Throwable): DownloadFailure {
        val causeChain = generateSequence(error) { it.cause }.toList()
        val http = causeChain.filterIsInstance<NativeDownloadHttpException>().firstOrNull()
        if (http != null) {
            return when (http.code) {
                401 -> failure(DownloadFailureKind.AUTH_REQUIRED, false, true, http.message)
                403 -> failure(DownloadFailureKind.FORBIDDEN, false, true, http.message)
                404, 410, 416 -> failure(DownloadFailureKind.NOT_FOUND, false, true, http.message)
                408 -> failure(DownloadFailureKind.TIMEOUT, true, false, http.message)
                429 -> failure(DownloadFailureKind.RATE_LIMITED, true, false, http.message)
                in 500..599 -> failure(DownloadFailureKind.SERVER, true, false, http.message)
                else -> failure(DownloadFailureKind.NETWORK, false, false, http.message)
            }
        }
        val first = causeChain.firstOrNull()
        val detail = causeChain.firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            ?: first?.javaClass?.simpleName
            ?: "Неизвестная ошибка"
        return when {
            causeChain.any { it is SocketTimeoutException } ->
                failure(DownloadFailureKind.TIMEOUT, true, false, detail)
            causeChain.any { it is UnknownHostException } ->
                failure(DownloadFailureKind.DNS, true, false, detail)
            causeChain.any { it is ConnectException } ->
                failure(DownloadFailureKind.CONNECTION, true, false, detail)
            causeChain.any { it is SSLException } ->
                failure(DownloadFailureKind.TLS, true, false, detail)
            causeChain.any { it is FileNotFoundException } ->
                failure(DownloadFailureKind.NOT_FOUND, false, true, detail)
            detail.contains("ENOSPC", ignoreCase = true) || detail.contains("No space left", ignoreCase = true) ->
                failure(DownloadFailureKind.STORAGE, false, false, detail)
            detail.contains("не поддерживается", ignoreCase = true) || detail.contains("unsupported", ignoreCase = true) ->
                failure(DownloadFailureKind.UNSUPPORTED, false, false, detail)
            causeChain.any { it is IOException } ->
                failure(DownloadFailureKind.NETWORK, true, false, detail)
            else -> failure(DownloadFailureKind.UNKNOWN, false, false, detail)
        }
    }

    private fun failure(
        kind: DownloadFailureKind,
        retryable: Boolean,
        refresh: Boolean,
        message: String?,
    ) = DownloadFailure(kind, retryable, refresh, message ?: kind.name)
}
