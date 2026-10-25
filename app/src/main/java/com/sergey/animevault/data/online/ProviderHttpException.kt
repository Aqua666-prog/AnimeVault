package com.sergey.animevault.data.online

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** No body, URL, headers or credentials in diagnostics. */
class ProviderHttpException(val sourceName: String, val statusCode: Int, val retryAfterMs: Long? = null) :
    IOException("$sourceName: HTTP $statusCode")

internal fun retryAfterMillis(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { seconds ->
        return seconds.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
    }
    return runCatching {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { isLenient = false }
        (format.parse(text)?.time?.minus(nowMs))?.coerceAtLeast(0)
    }.getOrNull()
}
