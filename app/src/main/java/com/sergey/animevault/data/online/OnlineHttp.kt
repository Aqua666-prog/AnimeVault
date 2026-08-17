package com.sergey.animevault.data.online

import com.sergey.animevault.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal suspend fun OkHttpClient.executeText(
    request: Request,
    sourceName: String,
): String = withContext(Dispatchers.IO) {
    try {
        newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OnlineSourceException(
                    "$sourceName вернул ошибку ${response.code}" +
                        body.takeIf(String::isNotBlank)?.let { ": ${it.take(160)}" }.orEmpty(),
                )
            }
            body
        }
    } catch (error: OnlineSourceException) {
        throw error
    } catch (error: IOException) {
        throw OnlineSourceException("Не удалось подключиться к $sourceName", error)
    }
}

internal fun Request.Builder.onlineHeaders(
    referer: String? = null,
    userAgent: String = animeVaultUserAgent(),
): Request.Builder = apply {
    header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
    header("User-Agent", userAgent)
    referer?.let { header("Referer", it) }
}

internal fun String.absoluteUrl(baseUrl: String): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> baseUrl.trimEnd('/') + this
    else -> baseUrl.trimEnd('/') + "/" + this
}

internal fun animeVaultUserAgent(platform: String = "Android"): String =
    "AnimeVault/${BuildConfig.VERSION_NAME} ($platform)"
