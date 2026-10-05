package com.sergey.animevault.data.metadata

import kotlinx.coroutines.CancellationException

interface TenraiMetadataFallback {
    suspend fun findOverviewByMalId(malId: Long): TenraiAnimeOverview?

    suspend fun searchAnime(
        query: String,
        page: Int,
        sfw: Boolean,
    ): TenraiPage<TenraiCatalogItem>? = null
}

class AniListTenraiMetadataFallback(
    private val repository: AniListMetadataRepository,
) : TenraiMetadataFallback {
    override suspend fun findOverviewByMalId(malId: Long): TenraiAnimeOverview? = try {
        repository.findAnimeByMalId(malId)?.toTenraiOverviewFallback(malId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override suspend fun searchAnime(
        query: String,
        page: Int,
        sfw: Boolean,
    ): TenraiPage<TenraiCatalogItem>? {
        if (page != 1) return null
        return try {
            val items = repository.searchAnime(query)
                .filter { candidate -> !sfw || candidate.isSafeFallbackCandidate() }
                .map(AniListMetadataCandidate::toTenraiCatalogFallback)
            TenraiPage(
                items = items,
                page = 1,
                lastPage = 1,
                hasNextPage = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

private fun AniListMetadataCandidate.toTenraiOverviewFallback(requestedMalId: Long): TenraiAnimeOverview =
    TenraiAnimeOverview(
        malId = malId ?: requestedMalId,
        title = canonicalTitle,
        englishTitle = englishTitle,
        japaneseTitle = nativeTitle,
        posterUrl = posterUrl,
        largePosterUrl = posterUrl,
        type = format,
        source = null,
        episodeCount = episodeCount,
        status = status,
        airing = status.equals("RELEASING", ignoreCase = true),
        duration = null,
        rating = null,
        score = averageScore?.div(10.0),
        scoredBy = null,
        rank = null,
        popularity = null,
        members = null,
        favorites = null,
        synopsis = description,
        background = null,
        season = null,
        year = year,
        broadcast = null,
        studios = emptyList(),
        genres = genres,
        relations = emptyList(),
        openings = emptyList(),
        endings = emptyList(),
        externalLinks = siteUrl?.let { listOf(TenraiNamedLink("AniList", it)) }.orEmpty(),
        streamingLinks = emptyList(),
        trailer = null,
        metadataSource = "AniList fallback",
        isStale = false,
    )

private fun AniListMetadataCandidate.toTenraiCatalogFallback(): TenraiCatalogItem = TenraiCatalogItem(
    malId = malId ?: anilistId,
    title = canonicalTitle,
    englishTitle = englishTitle,
    japaneseTitle = nativeTitle,
    imageUrl = posterUrl,
    type = format,
    episodeCount = episodeCount,
    status = status,
    score = averageScore?.div(10.0),
    year = year,
    season = null,
    broadcast = null,
)

private fun AniListMetadataCandidate.isSafeFallbackCandidate(): Boolean = true
