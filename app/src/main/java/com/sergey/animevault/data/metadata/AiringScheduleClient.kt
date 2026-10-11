package com.sergey.animevault.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public AniList schedule. No login or personal data required. */
data class VaultAiringEpisode(
    val mediaId: Int,
    val title: String,
    val episode: Int,
    val airingAtSeconds: Long,
    val coverUrl: String?,
)

internal fun parseVaultAiringEpisodes(json: String): Pair<List<VaultAiringEpisode>, Boolean> {
    val root = JSONObject(json)
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
        throw IllegalStateException(errors.optJSONObject(0)?.optString("message") ?: "AniList API error")
    }
    val page = root.optJSONObject("data")?.optJSONObject("Page")
        ?: throw IllegalStateException("AniList returned no schedule")
    val schedules = page.optJSONArray("airingSchedules")
    val episodes = buildList {
        if (schedules != null) for (index in 0 until schedules.length()) {
            val item = schedules.optJSONObject(index) ?: continue
            val media = item.optJSONObject("media") ?: continue
            val id = media.optInt("id")
            val episode = item.optInt("episode")
            val at = item.optLong("airingAt")
            if (id <= 0 || episode <= 0 || at <= 0) continue
            val titles = media.optJSONObject("title")
            val title = sequenceOf("english", "romaji", "native")
                .mapNotNull { key -> titles?.optString(key)?.takeIf(String::isNotBlank) }
                .firstOrNull() ?: continue
            add(VaultAiringEpisode(id, title, episode, at, media.optJSONObject("coverImage")?.optString("large")))
        }
    }
    return episodes to (page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false)
}

class AiringScheduleClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun week(startSeconds: Long, endSeconds: Long): List<VaultAiringEpisode> = withContext(Dispatchers.IO) {
        require(endSeconds > startSeconds)
        val collected = mutableListOf<VaultAiringEpisode>()
        // Cap network requests to avoid rate-limit spikes on busy seasonal weeks.
        for (page in 1..4) {
            val query = """query { Page(page: $page, perPage: 50) {
                pageInfo { hasNextPage }
                airingSchedules(airingAt_greater: $startSeconds, airingAt_lesser: $endSeconds, sort: TIME) {
                    airingAt episode media { id title { english romaji native } coverImage { large } }
                }
            } }""".trimIndent()
            val body = JSONObject().put("query", query).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .header("User-Agent", "AnimeVault/3.0 Android")
                .post(body)
                .build()
            val raw = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("AniList HTTP ${response.code}")
                response.body?.string() ?: throw IllegalStateException("AniList returned empty response")
            }
            val (batch, more) = parseVaultAiringEpisodes(raw)
            collected += batch
            if (!more) break
        }
        collected.distinctBy { it.mediaId to it.episode }.sortedBy(VaultAiringEpisode::airingAtSeconds)
    }
}
