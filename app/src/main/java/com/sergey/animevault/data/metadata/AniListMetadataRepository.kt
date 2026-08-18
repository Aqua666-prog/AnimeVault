package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lightweight AniList lookup for local-library metadata.
 *
 * Stage 2 may start one conservative lookup when a local title is opened. The
 * repository still never crawls the whole library during SAF scanning. Results are
 * cached and concurrent automatic/manual requests for the same session are serialized.
 */
class AniListMetadataRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val searchCache = InFlightRequestCache<String, List<AniListMetadataCandidate>>(
        maxEntries = MAX_CACHE_ENTRIES,
        ttlMs = CACHE_TTL_MS,
    )
    private val malCache = InFlightRequestCache<Long, AniListMetadataCandidate?>(
        maxEntries = MAX_MAL_CACHE_ENTRIES,
        ttlMs = CACHE_TTL_MS,
    )

    suspend fun searchAnime(query: String): List<AniListMetadataCandidate> {
        val cleanQuery = query.trim().replace(WHITESPACE_REGEX, " ")
        if (cleanQuery.length < MIN_QUERY_LENGTH) return emptyList()
        val key = cleanQuery.lowercase()
        return searchCache.getOrLoad(key) {
            val payload = AniListGraphQlRequest(
                query = SEARCH_QUERY,
                variables = mapOf("search" to cleanQuery),
            )
            val request = Request.Builder()
                .url(ANILIST_GRAPHQL_URL)
                .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                .onlineHeaders(userAgent = animeVaultUserAgent("Android; AniList metadata"))
                .header("Accept", "application/json")
                .build()
            parseAniListSearchResponse(gson, client.executeText(request, "AniList"))
        }
    }

    suspend fun findAnimeByMalId(malId: Long): AniListMetadataCandidate? {
        if (malId <= 0L || malId > Int.MAX_VALUE) return null
        return malCache.getOrLoad(malId) {
            val payload = AniListGraphQlRequest(
                query = MAL_ID_QUERY,
                variables = mapOf("malId" to malId.toInt()),
            )
            val request = Request.Builder()
                .url(ANILIST_GRAPHQL_URL)
                .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                .onlineHeaders(userAgent = animeVaultUserAgent("Android; AniList metadata"))
                .header("Accept", "application/json")
                .build()
            parseAniListMalIdResponse(gson, client.executeText(request, "AniList"))
        }
    }

    private companion object {
        const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        const val MIN_QUERY_LENGTH = 2
        const val MAX_CACHE_ENTRIES = 40
        const val MAX_MAL_CACHE_ENTRIES = 32
        const val CACHE_TTL_MS = 30 * 60_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val WHITESPACE_REGEX = Regex("\\s+")

        const val MAL_ID_QUERY = """
            query AnimeVaultMetadataByMal(${'$'}malId: Int!) {
              media: Media(idMal: ${'$'}malId, type: ANIME) {
                id
                idMal
                title { romaji english native }
                synonyms
                coverImage { extraLarge large color }
                bannerImage
                description(asHtml: false)
                seasonYear
                episodes
                format
                status
                genres
                averageScore
                siteUrl
              }
            }
        """

        const val SEARCH_QUERY = """
            query AnimeVaultMetadataSearch(${'$'}search: String!) {
              page: Page(page: 1, perPage: 10) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  id
                  idMal
                  title { romaji english native }
                  synonyms
                  coverImage { extraLarge large color }
                  bannerImage
                  description(asHtml: false)
                  seasonYear
                  episodes
                  format
                  status
                  genres
                  averageScore
                  siteUrl
                }
              }
            }
        """
    }

}

internal fun parseAniListSearchResponse(
    gson: Gson,
    json: String,
): List<AniListMetadataCandidate> {
    val response = gson.fromJson(json, AniListGraphQlResponse::class.java)
        ?: error("AniList вернул пустой ответ")
    response.errors.orEmpty().firstOrNull()?.message?.takeIf(String::isNotBlank)?.let { message ->
        error("AniList: $message")
    }
    return response.data?.page?.media.orEmpty()
        .mapNotNull(AniListMediaDto::toCandidate)
        .distinctBy(AniListMetadataCandidate::anilistId)
}

internal fun parseAniListMalIdResponse(
    gson: Gson,
    json: String,
): AniListMetadataCandidate? {
    val response = gson.fromJson(json, AniListGraphQlResponse::class.java)
        ?: error("AniList вернул пустой ответ")
    response.errors.orEmpty().firstOrNull()?.message?.takeIf(String::isNotBlank)?.let { message ->
        error("AniList: $message")
    }
    return response.data?.media?.toCandidate()
}

private fun AniListMediaDto.toCandidate(): AniListMetadataCandidate? {
    val id = id ?: return null
    val canonical = title?.romaji?.trim()?.takeIf(String::isNotBlank)
        ?: title?.english?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    return AniListMetadataCandidate(
        anilistId = id,
        malId = idMal,
        canonicalTitle = canonical,
        englishTitle = title?.english?.trim()?.takeIf(String::isNotBlank),
        nativeTitle = title?.native?.trim()?.takeIf(String::isNotBlank),
        synonyms = synonyms.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
        posterUrl = coverImage?.extraLarge?.takeIf(String::isNotBlank)
            ?: coverImage?.large?.takeIf(String::isNotBlank),
        bannerUrl = bannerImage?.takeIf(String::isNotBlank),
        accentHex = coverImage?.color?.takeIf(String::isNotBlank),
        description = sanitizeAniListDescription(description),
        year = seasonYear,
        episodeCount = episodes,
        format = format,
        status = status,
        genres = genres.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
        averageScore = averageScore,
        siteUrl = siteUrl?.takeIf(String::isNotBlank),
    )
}

internal fun sanitizeAniListDescription(value: String?): String? = value
    ?.replace(Regex("(?i)<br\\s*/?>"), "\n")
    ?.replace(Regex("\\[([^]]+)]\\([^)]*\\)")) { match -> match.groupValues[1] }
    ?.replace(Regex("(?m)(\\*\\*|__|~~|`)"), "")
    ?.replace(Regex("\\n{3,}"), "\n\n")
    ?.trim()
    ?.takeIf(String::isNotBlank)

data class AniListMetadataCandidate(
    val anilistId: Long,
    val malId: Long?,
    val canonicalTitle: String,
    val englishTitle: String?,
    val nativeTitle: String?,
    val synonyms: List<String>,
    val posterUrl: String?,
    val bannerUrl: String?,
    val accentHex: String?,
    val description: String?,
    val year: Int?,
    val episodeCount: Int?,
    val format: String?,
    val status: String?,
    val genres: List<String>,
    val averageScore: Int?,
    val siteUrl: String?,
)

private data class AniListGraphQlRequest(
    val query: String,
    val variables: Map<String, Any>,
)

private data class AniListGraphQlResponse(
    val data: AniListDataDto? = null,
    val errors: List<AniListErrorDto>? = null,
)

private data class AniListDataDto(
    val page: AniListPageDto? = null,
    val media: AniListMediaDto? = null,
)

private data class AniListPageDto(
    val media: List<AniListMediaDto>? = null,
)

private data class AniListErrorDto(
    val message: String? = null,
)

private data class AniListMediaDto(
    val id: Long? = null,
    val idMal: Long? = null,
    val title: AniListTitleDto? = null,
    val synonyms: List<String>? = null,
    val coverImage: AniListCoverImageDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val siteUrl: String? = null,
)

private data class AniListTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

private data class AniListCoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val color: String? = null,
)
