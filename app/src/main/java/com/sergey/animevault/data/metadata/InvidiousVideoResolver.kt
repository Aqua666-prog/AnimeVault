package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResolvedInvidiousStream(
    val url: String,
    val mimeType: String?,
    val qualityLabel: String?,
    val instance: String,
)

/**
 * Resolves a YouTube id through public Invidious instances and returns a
 * progressive (audio + video) stream that Media3 can play natively.
 *
 * This deliberately avoids YouTube's iframe/WebView player. On some Android
 * 16 / Samsung WebView builds the iframe can play audio while rendering a
 * black video surface.
 */
class InvidiousVideoResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val instances: List<String> = DEFAULT_INSTANCES,
) {
    suspend fun resolve(
        videoId: String,
        excludedInstances: Set<String> = emptySet(),
    ): ResolvedInvidiousStream? {
        val cleanId = videoId.trim()
        if (!YOUTUBE_ID.matches(cleanId)) return null

        for (instance in instances) {
            val normalizedInstance = instance.trimEnd('/')
            if (normalizedInstance in excludedInstances) continue

            try {
                resolveOnInstance(normalizedInstance, cleanId)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Public instances can be rate-limited or temporarily broken.
                // Try the next one instead of failing the whole player.
            }
        }
        return null
    }

    private suspend fun resolveOnInstance(
        instance: String,
        videoId: String,
    ): ResolvedInvidiousStream? {
        val baseUrl = instance.toHttpUrl()
        val apiUrl = baseUrl.newBuilder()
            .addPathSegments("api/v1/videos")
            .addPathSegment(videoId)
            .addQueryParameter("local", "true")
            .build()

        val request = Request.Builder()
            .url(apiUrl)
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; Invidious resolver"))
            .header("Accept", "application/json")
            .build()

        val payload = gson.fromJson(
            client.executeText(request, "Invidious ${baseUrl.host}"),
            InvidiousVideoDto::class.java,
        ) ?: return null

        val progressive = payload.formatStreams.orEmpty()
            .asSequence()
            .filter { !it.url.isNullOrBlank() }
            .sortedWith(
                compareByDescending<InvidiousFormatDto> {
                    qualityHeight(it.qualityLabel ?: it.resolution)
                }
                    .thenByDescending { it.container.equals("mp4", ignoreCase = true) }
                    .thenByDescending { it.bitrate?.toLongOrNull() ?: 0L },
            )
            .firstOrNull()

        if (progressive != null) {
            return ResolvedInvidiousStream(
                url = normalizeStreamUrl(baseUrl, progressive.url.orEmpty()),
                mimeType = progressive.type
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                qualityLabel = progressive.qualityLabel
                    ?.takeIf(String::isNotBlank)
                    ?: progressive.resolution?.takeIf(String::isNotBlank),
                instance = instance,
            )
        }

        val hls = payload.hlsUrl?.trim()?.takeIf(String::isNotBlank)
        if (hls != null) {
            return ResolvedInvidiousStream(
                url = normalizeStreamUrl(baseUrl, hls),
                mimeType = "application/x-mpegURL",
                qualityLabel = "HLS",
                instance = instance,
            )
        }

        return null
    }

    private fun normalizeStreamUrl(baseUrl: HttpUrl, value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> baseUrl.resolve(value)?.toString() ?: value
        else -> value
    }

    private companion object {
        val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{6,20}$")

        // Current public instances from the official Invidious instance list.
        val DEFAULT_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yt.chocolatemoo53.com",
            "https://invidious.tiekoetter.com",
        )
    }
}

private data class InvidiousVideoDto(
    val hlsUrl: String? = null,
    val formatStreams: List<InvidiousFormatDto>? = null,
)

private data class InvidiousFormatDto(
    val url: String? = null,
    val itag: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val bitrate: String? = null,
    val container: String? = null,
    val encoding: String? = null,
    val qualityLabel: String? = null,
    val resolution: String? = null,
)

private fun qualityHeight(value: String?): Int =
    Regex("(\\d{3,4})")
        .find(value.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0
