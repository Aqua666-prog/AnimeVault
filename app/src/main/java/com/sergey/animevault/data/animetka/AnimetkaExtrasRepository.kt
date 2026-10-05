package com.sergey.animevault.data.animetka

import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineTitleMatcher
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

class AnimetkaExtrasRepository internal constructor(
    private val api: AnimetkaApi,
    private val enabled: () -> Boolean = { true },
) {
    private val mappingCache = ConcurrentHashMap<String, String>()

    internal suspend fun trailersFor(release: OnlineReleaseDetails): List<AnimetkaTrailerDto> {
        if (!enabled()) return emptyList()
        val animetkaId = resolveAnimetkaId(release) ?: return emptyList()
        return try {
            AnimetkaJson.parseTrailers(api.trailers(animetkaId), animetkaId).map { trailer ->
                trailer.copy(
                    url = trailer.url.toAnimetkaAbsoluteUrl(),
                    previewUrl = trailer.previewUrl?.toAnimetkaAbsoluteUrl(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    internal suspend fun resolveAnimetkaId(release: OnlineReleaseDetails): String? {
        if (!enabled()) return null
        if (release.providerId == OnlineProviderIds.ANIMETKA) return release.id
        val cacheKey = release.mappingKey()
        mappingCache[cacheKey]?.let { cached -> return cached.takeUnless { it == NO_MATCH } }

        val query = release.englishName?.takeIf(String::isNotBlank) ?: release.name
        if (query.isBlank()) return cacheResult(cacheKey, null)
        val candidates = try {
            AnimetkaJson.parseCatalog(api.search(query, MATCH_LIMIT, 0)).items
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return cacheResult(cacheKey, null)
        }
        if (candidates.isEmpty()) return cacheResult(cacheKey, null)

        val shikimori = release.externalIds.shikimoriId
        if (shikimori != null) {
            val exact = candidates.filter { it.externalIds.shikimoriId == shikimori }
            if (exact.size == 1) return cacheResult(cacheKey, exact.single().id)
            if (exact.size > 1) return cacheResult(cacheKey, null)
        }
        val mal = release.externalIds.malId
        if (mal != null) {
            val exact = candidates.filter { it.externalIds.malId == mal }
            if (exact.size == 1) return cacheResult(cacheKey, exact.single().id)
            if (exact.size > 1) return cacheResult(cacheKey, null)
        }
        val anilist = release.externalIds.anilistId
        if (anilist != null) {
            val exact = candidates.filter { it.externalIds.anilistId == anilist }
            if (exact.size == 1) return cacheResult(cacheKey, exact.single().id)
            if (exact.size > 1) return cacheResult(cacheKey, null)
        }

        val sourceCard = release.asCardForMatching()
        val scored = candidates
            .map { candidate -> candidate to OnlineTitleMatcher.score(sourceCard, candidate.toOnlineCard()) }
            .filter { it.second >= MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return cacheResult(cacheKey, null)
        val second = scored.getOrNull(1)
        if (second != null && second.second >= best.second - AMBIGUITY_MARGIN) return cacheResult(cacheKey, null)
        return cacheResult(cacheKey, best.first.id)
    }

    private fun cacheResult(key: String, value: String?): String? {
        mappingCache[key] = value ?: NO_MATCH
        return value
    }

    private fun OnlineReleaseDetails.mappingKey(): String = buildString {
        append(providerId).append('|').append(id)
        append('|').append(externalIds.shikimoriId ?: 0L)
        append('|').append(externalIds.malId ?: 0L)
        append('|').append(externalIds.anilistId ?: 0L)
        append('|').append(name.trim().lowercase())
        append('|').append(englishName.orEmpty().trim().lowercase())
        append('|').append(year ?: 0)
        append('|').append(type.orEmpty().trim().lowercase())
        append('|').append(season.orEmpty().trim().lowercase())
    }

    private fun OnlineReleaseDetails.asCardForMatching() = OnlineReleaseCard(
        providerId = providerId,
        providerName = providerName,
        id = id,
        alias = alias,
        name = name,
        englishName = englishName,
        posterUrl = posterUrl,
        year = year,
        type = type,
        season = season,
        episodeCount = episodeCount,
        isOngoing = isOngoing,
        genres = genres,
        externalIds = externalIds,
    )

    private companion object {
        const val MATCH_LIMIT = 12
        const val MATCH_THRESHOLD = 72
        const val AMBIGUITY_MARGIN = 4
        const val NO_MATCH = "__no_match__"
    }
}
