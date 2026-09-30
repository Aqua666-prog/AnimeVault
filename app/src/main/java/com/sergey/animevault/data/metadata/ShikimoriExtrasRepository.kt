package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ShikimoriVideo(
    val id: String,
    val name: String?,
    val kind: String,
    val url: String?,
    val playerUrl: String?,
    val imageUrl: String?,
)

data class ShikimoriCharacter(
    val id: String,
    val malId: Long?,
    val name: String,
    val russianName: String?,
    val japaneseName: String?,
    val synonyms: List<String>,
    val imageUrl: String?,
    val roleRu: String?,
    val roleEn: String?,
    val description: String?,
)

data class ShikimoriAnimeExtras(
    val shikimoriId: Long?,
    val malId: Long?,
    val name: String,
    val russianName: String?,
    val englishName: String?,
    val japaneseName: String?,
    val year: Int?,
    val videos: List<ShikimoriVideo>,
    val characters: List<ShikimoriCharacter>,
)

/**
 * Public Shikimori GraphQL client used as both a Russian metadata source and a
 * fallback video catalogue. The lightweight feed query intentionally omits
 * character roles; the full query is only made from a title page.
 */
class ShikimoriExtrasRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val videoCache = InFlightRequestCache<String, ShikimoriAnimeExtras?>(
        maxEntries = 128,
        ttlMs = CACHE_TTL_MS,
    )
    private val fullCache = InFlightRequestCache<String, ShikimoriAnimeExtras?>(
        maxEntries = 64,
        ttlMs = CACHE_TTL_MS,
    )

    suspend fun getVideos(release: OnlineReleaseDetails): ShikimoriAnimeExtras? =
        videoCache.getOrLoad(release.cacheKey()) {
            queryAndChoose(release, withCharacters = false)
        }

    suspend fun getExtras(release: OnlineReleaseDetails): ShikimoriAnimeExtras? =
        fullCache.getOrLoad(release.cacheKey()) {
            queryAndChoose(release, withCharacters = true)
        }

    private suspend fun queryAndChoose(
        release: OnlineReleaseDetails,
        withCharacters: Boolean,
    ): ShikimoriAnimeExtras? {
        val directShikimoriId = release.externalIds.shikimoriId
        if (directShikimoriId != null) {
            val directCandidates = executeQuery(
                query = if (withCharacters) FULL_QUERY else VIDEOS_QUERY,
                variables = mapOf("ids" to directShikimoriId.toString()),
            )
            val exact = directCandidates.firstOrNull { anime ->
                anime.id?.toLongOrNull() == directShikimoriId
            } ?: directCandidates.firstOrNull()
            if (exact != null) return exact.toDomain()
        }

        val queries = listOfNotNull(
            release.englishName?.trim()?.takeIf(String::isNotBlank),
            release.name.trim().takeIf(String::isNotBlank),
        ).distinct()
        for (queryText in queries) {
            val candidates = executeQuery(
                query = if (withCharacters) FULL_QUERY else VIDEOS_QUERY,
                variables = mapOf("search" to queryText),
            )
            chooseCandidate(release, candidates)?.let { return it.toDomain() }
        }
        return null
    }

    private suspend fun executeQuery(
        query: String,
        variables: Map<String, Any>,
    ): List<ShikimoriAnimeDto> {
        val payload = ShikimoriGraphQlRequest(query = query, variables = variables)
        val request = Request.Builder()
            .url(SHIKIMORI_GRAPHQL_URL)
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; Shikimori extras"))
            .header("Accept", "application/json")
            .build()
        val envelope = gson.fromJson(
            client.executeText(request, "Shikimori"),
            ShikimoriGraphQlEnvelope::class.java,
        ) ?: error("Shikimori вернул пустой ответ")
        envelope.errors.orEmpty().firstOrNull()?.message?.takeIf(String::isNotBlank)?.let(::error)
        return envelope.data?.animes.orEmpty()
    }

    private fun chooseCandidate(
        release: OnlineReleaseDetails,
        candidates: List<ShikimoriAnimeDto>,
    ): ShikimoriAnimeDto? {
        val targetNames = listOfNotNull(release.englishName, release.name)
            .map(::normalizeName)
            .filter(String::isNotBlank)
            .toSet()
        return candidates
            .map { candidate ->
                val candidateNames = buildList {
                    candidate.name?.let(::add)
                    candidate.russian?.let(::add)
                    candidate.english?.let(::add)
                    candidate.japanese?.let(::add)
                    addAll(candidate.synonyms.orEmpty())
                }.map(::normalizeName).filter(String::isNotBlank).toSet()
                var score = candidateNames.intersect(targetNames).size * 100
                if (targetNames.any { target ->
                        candidateNames.any { value -> value.contains(target) || target.contains(value) }
                    }
                ) {
                    score += 25
                }
                if (release.year != null && candidate.airedOn?.year == release.year) score += 20
                if (release.externalIds.malId != null && candidate.malId?.toLongOrNull() == release.externalIds.malId) {
                    score += 200
                }
                candidate to score
            }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score >= MIN_MATCH_SCORE }
            ?.first
    }

    private fun ShikimoriAnimeDto.toDomain(): ShikimoriAnimeExtras = ShikimoriAnimeExtras(
        shikimoriId = id?.toLongOrNull(),
        malId = malId?.toLongOrNull(),
        name = name?.trim().orEmpty().ifBlank { english?.trim().orEmpty().ifBlank { russian?.trim().orEmpty() } },
        russianName = russian?.trim()?.takeIf(String::isNotBlank),
        englishName = english?.trim()?.takeIf(String::isNotBlank),
        japaneseName = japanese?.trim()?.takeIf(String::isNotBlank),
        year = airedOn?.year,
        videos = videos.orEmpty().mapNotNull { video ->
            val id = video.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val kind = video.kind?.trim()?.takeIf(String::isNotBlank) ?: "video"
            ShikimoriVideo(
                id = id,
                name = video.name?.trim()?.takeIf(String::isNotBlank),
                kind = kind,
                url = normalizeUrl(video.url),
                playerUrl = normalizeUrl(video.playerUrl),
                imageUrl = normalizeUrl(video.imageUrl),
            )
        },
        characters = characterRoles.orEmpty().mapNotNull { role ->
            val character = role.character ?: return@mapNotNull null
            val id = character.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = character.name?.trim().orEmpty()
                .ifBlank { character.russian?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }
            ShikimoriCharacter(
                id = id,
                malId = character.malId?.toLongOrNull(),
                name = name,
                russianName = character.russian?.trim()?.takeIf(String::isNotBlank),
                japaneseName = character.japanese?.trim()?.takeIf(String::isNotBlank),
                synonyms = character.synonyms.orEmpty().map(String::trim).filter(String::isNotBlank),
                imageUrl = normalizeUrl(character.poster?.mainUrl)
                    ?: normalizeUrl(character.poster?.mainAltUrl)
                    ?: normalizeUrl(character.poster?.originalUrl),
                roleRu = role.rolesRu.orEmpty().firstOrNull()?.trim()?.takeIf(String::isNotBlank),
                roleEn = role.rolesEn.orEmpty().firstOrNull()?.trim()?.takeIf(String::isNotBlank),
                description = character.description?.trim()?.takeIf(String::isNotBlank),
            )
        },
    )

    private fun OnlineReleaseDetails.cacheKey(): String = buildString {
        append(providerId).append('|').append(id)
        append('|').append(externalIds.shikimoriId ?: 0L)
        append('|').append(externalIds.malId ?: 0L)
        append('|').append(englishName.orEmpty())
        append('|').append(year ?: 0)
    }

    private companion object {
        const val SHIKIMORI_GRAPHQL_URL = "https://shikimori.one/api/graphql"
        const val CACHE_TTL_MS = 2 * 60 * 60_000L
        const val MIN_MATCH_SCORE = 25
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val VIDEOS_QUERY = """
            query AnimeVaultShikimoriVideos(${'$'}ids: String, ${'$'}search: String) {
              animes(ids: ${'$'}ids, search: ${'$'}search, limit: 5, order: popularity) {
                id
                malId
                name
                russian
                english
                japanese
                synonyms
                airedOn { year }
                videos { id name url kind playerUrl imageUrl }
              }
            }
        """

        const val FULL_QUERY = """
            query AnimeVaultShikimoriExtras(${'$'}ids: String, ${'$'}search: String) {
              animes(ids: ${'$'}ids, search: ${'$'}search, limit: 5, order: popularity) {
                id
                malId
                name
                russian
                english
                japanese
                synonyms
                airedOn { year }
                videos { id name url kind playerUrl imageUrl }
                characterRoles {
                  id
                  rolesRu
                  rolesEn
                  character {
                    id
                    malId
                    name
                    russian
                    japanese
                    synonyms
                    description
                    poster { originalUrl mainUrl mainAltUrl }
                  }
                }
              }
            }
        """
    }
}

private fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun normalizeUrl(value: String?): String? {
    val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://") || clean.startsWith("https://") -> clean
        clean.startsWith("/") -> "https://shikimori.one$clean"
        else -> clean
    }
}

private data class ShikimoriGraphQlRequest(
    val query: String,
    val variables: Map<String, Any>,
)

private data class ShikimoriGraphQlEnvelope(
    val data: ShikimoriGraphQlData? = null,
    val errors: List<ShikimoriGraphQlError>? = null,
)

private data class ShikimoriGraphQlData(
    val animes: List<ShikimoriAnimeDto>? = null,
)

private data class ShikimoriGraphQlError(
    val message: String? = null,
)

private data class ShikimoriAnimeDto(
    val id: String? = null,
    val malId: String? = null,
    val name: String? = null,
    val russian: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String>? = null,
    val airedOn: ShikimoriDateDto? = null,
    val videos: List<ShikimoriVideoDto>? = null,
    val characterRoles: List<ShikimoriCharacterRoleDto>? = null,
)

private data class ShikimoriDateDto(
    val year: Int? = null,
)

private data class ShikimoriVideoDto(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val kind: String? = null,
    val playerUrl: String? = null,
    val imageUrl: String? = null,
)

private data class ShikimoriCharacterRoleDto(
    val id: String? = null,
    val rolesRu: List<String>? = null,
    val rolesEn: List<String>? = null,
    val character: ShikimoriCharacterDto? = null,
)

private data class ShikimoriCharacterDto(
    val id: String? = null,
    val malId: String? = null,
    val name: String? = null,
    val russian: String? = null,
    val japanese: String? = null,
    val synonyms: List<String>? = null,
    val description: String? = null,
    val poster: ShikimoriPosterDto? = null,
)

private data class ShikimoriPosterDto(
    val originalUrl: String? = null,
    val mainUrl: String? = null,
    val mainAltUrl: String? = null,
)
