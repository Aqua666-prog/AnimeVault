package com.sergey.animevault.ui.online

import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A new season is opened only if provider metadata verifies the exact sequel's AniList/MAL ID. */
data class NextSeasonTarget(
    val providerId: String,
    val releaseId: String,
    val episodeId: String,
    val title: String,
)

internal data class AniListSequel(val id: Long, val malId: Long?, val titles: List<String>)

/** Reject ambiguous relations, spinoffs and adaptations: guessing season by title is unsafe. */
internal fun parseUniqueAniListSequel(json: String): AniListSequel? {
    val root = JSONObject(json)
    if ((root.optJSONArray("errors")?.length() ?: 0) > 0) return null
    val media = root.optJSONObject("data")?.optJSONObject("Media") ?: return null
    val edges = media.optJSONObject("relations")?.optJSONArray("edges") ?: return null
    val sequels = buildList {
        for (index in 0 until edges.length()) {
            val edge = edges.optJSONObject(index) ?: continue
            if (edge.optString("relationType") != "SEQUEL") continue
            val node = edge.optJSONObject("node") ?: continue
            val id = node.optLong("id").takeIf { it > 0L } ?: continue
            val titles = node.optJSONObject("title")
            val names = listOfNotNull(
                titles?.optString("romaji")?.takeIf(String::isNotBlank),
                titles?.optString("english")?.takeIf(String::isNotBlank),
                titles?.optString("native")?.takeIf(String::isNotBlank),
            ).distinct()
            if (names.isEmpty()) continue
            add(AniListSequel(id, node.optLong("idMal").takeIf { it > 0L }, names))
        }
    }.distinctBy(AniListSequel::id)
    return sequels.singleOrNull()
}

internal fun sequelIdsMatch(ids: ExternalAnimeIds, sequel: AniListSequel): Boolean =
    ids.anilistId == sequel.id ||
        (sequel.malId != null && (ids.malId == sequel.malId || ids.shikimoriId == sequel.malId))

class NextSeasonResolver(
    private val repository: OnlineRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun find(release: OnlineReleaseDetails): NextSeasonTarget? {
        val aniListId = release.externalIds.anilistId?.takeIf { it > 0 }
        val malId = (release.externalIds.malId ?: release.externalIds.shikimoriId)?.takeIf { it > 0 }
        if (aniListId == null && malId == null) return null
        val selector = if (aniListId != null) "id: $aniListId" else "idMal: $malId"
        val query = """query { Media($selector, type: ANIME) {
            relations { edges { relationType(version: 2) node {
                id idMal title { romaji english native }
            } } }
        } }""".trimIndent()
        val sequel = withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .header("User-Agent", "AnimeVault/3.0 Android")
                .post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseUniqueAniListSequel(response.body?.string() ?: return@withContext null)
            }
        } ?: return null
        // Never cross providers implicitly: same audio/source preferences must still make sense.
        for (title in sequel.titles.take(2)) {
            val catalog = try {
                repository.getCatalog(release.providerId, page = 1, limit = 30, search = title)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue
            }
            // Require exact external IDs. Textual similarity alone could launch the wrong franchise.
            val matches = catalog.releases.filter { sequelIdsMatch(it.externalIds, sequel) }
            if (matches.size != 1) continue
            val candidate = matches.single()
            if (candidate.id == release.id) continue
            val details = try {
                repository.getRelease(candidate.providerId, candidate.id)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue
            }
            if (!sequelIdsMatch(details.externalIds, sequel)) continue
            val episode = details.episodes.filter { it.hasStream }
                .minWithOrNull(compareBy({ it.ordinal ?: Double.MAX_VALUE }, { it.sortOrder ?: Double.MAX_VALUE }))
                ?: continue
            return NextSeasonTarget(details.providerId, details.id, episode.id, details.name)
        }
        return null
    }
}
