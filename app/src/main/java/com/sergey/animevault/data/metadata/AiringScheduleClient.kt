package com.sergey.animevault.data.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Public AniList schedule. Times are UTC seconds, displayed in the phone's local time zone. */
data class VaultAiringEpisode(
    val mediaId: Int,
    val title: String,
    val episode: Int,
    val airingAtSeconds: Long,
    val coverUrl: String?,
    /** MAL ID is the identity used by Shikimori, not the AniList media ID. */
    val malId: Int? = null,
    /** ISO 3166 country code supplied by AniList: JP, CN, KR, ... */
    val countryCode: String? = null,
    /** Original title for searching provider catalogues if a translation is unavailable. */
    val searchTitle: String = title,
)

/** org.json's optString turns JSON null into the literal string "null". */
internal fun validAiringTitle(value: String?): String? = value?.trim()
    ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

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
            val english = validAiringTitle(titles?.optString("english"))
            val romaji = validAiringTitle(titles?.optString("romaji"))
            val native = validAiringTitle(titles?.optString("native"))
            val display = english ?: romaji ?: native ?: "Без названия · AniList #$id"
            val search = romaji ?: english ?: native ?: ""
            val malId = media.optInt("idMal").takeIf { it > 0 }
            val origin = validAiringTitle(media.optString("countryOfOrigin"))?.uppercase()
            val cover = validAiringTitle(media.optJSONObject("coverImage")?.optString("large"))
                ?.takeIf { it.startsWith("https://") }
            add(VaultAiringEpisode(id, display, episode, at, cover, malId, origin, search))
        }
    }
    return episodes to (page.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false)
}

/** Accept only true Russian titles from the Shikimori GraphQL response. */
internal fun parseRussianAiringTitles(json: String): Map<Int, String> {
    val root = JSONObject(json)
    if ((root.optJSONArray("errors")?.length() ?: 0) > 0) {
        throw IllegalStateException("Shikimori rejected the title query")
    }
    val items = root.optJSONObject("data")?.optJSONArray("animes")
        ?: throw IllegalStateException("Shikimori returned no anime list")
    val result = mutableMapOf<Int, String>()
    for (index in 0 until items.length()) {
        val anime = items.optJSONObject(index) ?: continue
        val id = anime.optString("id").toIntOrNull() ?: continue
        val title = validAiringTitle(anime.optString("russian")) ?: continue
        // Shikimori sometimes has the romanized title in russian; retain AniList then.
        if (title.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' }) result[id] = title
    }
    return result
}

internal fun applyRussianAiringTitles(
    entries: List<VaultAiringEpisode>, byMalId: Map<Int, String>,
): List<VaultAiringEpisode> = entries.map { entry ->
    val translated = entry.malId?.let(byMalId::get)
    if (translated == null) entry else entry.copy(title = translated)
}

class AiringScheduleClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build(),
) {
    private val titleCache = ConcurrentHashMap<Int, String>()

    suspend fun week(startSeconds: Long, endSeconds: Long): List<VaultAiringEpisode> =
        withContext(Dispatchers.IO) {
            require(endSeconds > startSeconds)
            val collected = mutableListOf<VaultAiringEpisode>()
            // Bounded pagination protects the API; only entries from the requested window.
            for (page in 1..4) {
                val query = """query { Page(page: $page, perPage: 50) {
                    pageInfo { hasNextPage }
                    airingSchedules(airingAt_greater: $startSeconds, airingAt_lesser: $endSeconds, sort: TIME) {
                        airingAt episode media {
                            id idMal countryOfOrigin title { english romaji native }
                            coverImage { large }
                        }
                    }
                } }""".trimIndent()
                val body = JSONObject().put("query", query).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://graphql.anilist.co")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AnimeVault/3.0.3 Android")
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
            collected.distinctBy { it.mediaId to it.episode }
                .sortedBy(VaultAiringEpisode::airingAtSeconds)
        }

    /** Optional enrichment: a Shikimori outage must never break the AniList calendar. */
    suspend fun withRussianTitles(entries: List<VaultAiringEpisode>): List<VaultAiringEpisode> =
        withContext(Dispatchers.IO) {
            if (entries.isEmpty()) return@withContext entries
            val missing = entries.mapNotNull(VaultAiringEpisode::malId).distinct()
                .filterNot(titleCache::containsKey)
            // At most 120 identities / 3 batches per week refresh. Never one request per anime.
            for ((index, batch) in missing.take(120).chunked(40).withIndex()) {
                if (index > 0) delay(450)
                val ids = batch.joinToString(",")
                val query = """{ animes(ids: "$ids", limit: 40) { id russian } }"""
                val request = Request.Builder()
                    .url("https://shikimori.one/api/graphql")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AnimeVault/3.0.3 Android")
                    .post(JSONObject().put("query", query).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                try {
                    val raw = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException(
                            "Shikimori HTTP ${response.code}",
                        )
                        response.body?.string() ?: throw IllegalStateException("Empty Shikimori response")
                    }
                    titleCache.putAll(parseRussianAiringTitles(raw))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Preserve AniList's original titles; don't show an error on every card.
                    break
                }
            }
            applyRussianAiringTitles(entries, titleCache)
        }
}
