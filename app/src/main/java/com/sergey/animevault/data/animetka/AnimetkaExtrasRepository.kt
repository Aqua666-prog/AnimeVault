package com.sergey.animevault.data.animetka

import com.sergey.animevault.data.metadata.ShikimoriCharacter
import com.sergey.animevault.data.metadata.CharacterLoadDiagnostic
import com.sergey.animevault.data.metadata.CharacterLoadState
import com.sergey.animevault.data.metadata.CharacterLoadReason
import com.sergey.animevault.data.metadata.CharacterSource
import com.sergey.animevault.data.metadata.CharacterStage
import com.sergey.animevault.data.metadata.characterRequest
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.online.ExternalAnimeIds
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineTitleMatcher
import java.util.concurrent.ConcurrentHashMap

class AnimetkaExtrasRepository internal constructor(
    private val api: AnimetkaApi,
    private val enabled: () -> Boolean = { true },
) {
    private val characterCache = InFlightRequestCache<String, ShikimoriCharacter>(maxEntries = 128, ttlMs = 2 * 60 * 60_000L)
    private val mappingCache = ConcurrentHashMap<String, String>()

    internal suspend fun trailersFor(release: OnlineReleaseDetails): List<AnimetkaTrailerDto> {
        if (!enabled()) return emptyList()
        val animetkaId = resolveAnimetkaId(release) ?: return emptyList()
        return AnimetkaJson.parseTrailers(api.trailers(animetkaId), animetkaId).map { trailer ->
            trailer.copy(
                url = trailer.url.toAnimetkaAbsoluteUrl(),
                previewUrl = trailer.previewUrl?.toAnimetkaAbsoluteUrl(),
            )
        }
    }

    /** Null means no verified catalogue mapping, while an empty list is a successful response. */
    internal suspend fun charactersFor(
        release: OnlineReleaseDetails,
        record: (CharacterLoadDiagnostic) -> Unit = {},
    ): List<ShikimoriCharacter>? {
        val id = resolveAnimetkaId(release, requireExternalId = true, record = record) ?: return null
        return characterRequest(CharacterSource.ANIMETKA, CharacterStage.CHARACTERS) {
            parseAnimetkaCharacters(api.characters(id))
        }
    }

    internal suspend fun characterDetails(shikimoriCharacterId: String): ShikimoriCharacter {
        check(enabled()) { "Аниметка отключена" }
        require(shikimoriCharacterId.toLongOrNull()?.let { it > 0 } == true)
        return characterCache.getOrLoad(shikimoriCharacterId) {
            val character = parseAnimetkaCharacter(api.characterDetails(shikimoriCharacterId))
            check(character.id == shikimoriCharacterId) { "Аниметка вернула другого персонажа" }
            character
        }
    }

    internal suspend fun resolveAnimetkaId(
        release: OnlineReleaseDetails,
        requireExternalId: Boolean = false,
        record: (CharacterLoadDiagnostic) -> Unit = {},
    ): String? {
        fun mapping(reason: CharacterLoadReason, matched: Boolean = false, animetkaId: String? = null) = record(CharacterLoadDiagnostic(
            CharacterSource.ANIMETKA, CharacterStage.MAPPING,
            if (matched) CharacterLoadState.SUCCESS else CharacterLoadState.SKIPPED, reason,
            shikimoriAnimeId = release.externalIds.shikimoriId,
            malAnimeId = release.externalIds.malId,
            anilistAnimeId = release.externalIds.anilistId,
            animetkaAnimeId = animetkaId?.toLongOrNull()?.takeIf { it > 0 },
        ))
        if (!enabled()) { mapping(CharacterLoadReason.DISABLED); return null }
        record(CharacterLoadDiagnostic(CharacterSource.ANIMETKA, CharacterStage.MAPPING, CharacterLoadState.STARTED,
            shikimoriAnimeId = release.externalIds.shikimoriId,
            malAnimeId = release.externalIds.malId, anilistAnimeId = release.externalIds.anilistId,
        ))
        if (release.providerId == OnlineProviderIds.ANIMETKA) {
            mapping(CharacterLoadReason.OK, matched = true, animetkaId = release.id)
            return release.id
        }
        if (requireExternalId && !release.externalIds.hasAny) {
            mapping(CharacterLoadReason.MISSING_IDS)
            return null
        }
        val cacheKey = "${release.mappingKey()}|$requireExternalId"
        mappingCache[cacheKey]?.let {
            mapping(CharacterLoadReason.CACHE_HIT, matched = true, animetkaId = it)
            return it
        }

        val query = release.englishName?.takeIf(String::isNotBlank) ?: release.name
        if (query.isBlank()) { mapping(CharacterLoadReason.NO_MATCH); return null }
        var candidates = characterRequest(CharacterSource.ANIMETKA, CharacterStage.SEARCH) {
            val root = api.search(query, MATCH_LIMIT, 0, ANIMETKA_SEARCH_ATTRIBUTES)
            if (requireExternalId) check(root.isJsonArray) { "Expected Animetka search array" }
            AnimetkaJson.parseCatalog(root).items
        }
        if (candidates.isEmpty()) { mapping(CharacterLoadReason.NO_MATCH); return null }

        // Search fields can be incomplete. Verify details before accepting a foreign title.
        if (requireExternalId) {
            candidates = candidates.map { candidate ->
                if (hasSharedId(release.externalIds, candidate.externalIds)) candidate
                else characterRequest(CharacterSource.ANIMETKA, CharacterStage.DETAILS) {
                    AnimetkaJson.parseAnime(api.details(candidate.id))
                        ?.also { check(it.id == candidate.id) { "Аниметка вернула другой тайтл" } }
                        ?: error("Аниметка не вернула карточку тайтла")
                }
            }
        }
        candidates = candidates.filterNot { hasConflictingIds(release.externalIds, it.externalIds) }
        val shikimori = release.externalIds.shikimoriId
        if (shikimori != null) {
            val exact = candidates.filter { it.externalIds.shikimoriId == shikimori }
            if (exact.size == 1) {
                mapping(CharacterLoadReason.OK, matched = true, animetkaId = exact.single().id)
                return cacheResult(cacheKey, exact.single().id)
            }
            if (exact.size > 1) { mapping(CharacterLoadReason.NO_MATCH); return null }
        }
        val mal = release.externalIds.malId
        if (mal != null) {
            val exact = candidates.filter { it.externalIds.malId == mal }
            if (exact.size == 1) {
                mapping(CharacterLoadReason.OK, matched = true, animetkaId = exact.single().id)
                return cacheResult(cacheKey, exact.single().id)
            }
            if (exact.size > 1) { mapping(CharacterLoadReason.NO_MATCH); return null }
        }
        val anilist = release.externalIds.anilistId
        if (anilist != null) {
            val exact = candidates.filter { it.externalIds.anilistId == anilist }
            if (exact.size == 1) {
                mapping(CharacterLoadReason.OK, matched = true, animetkaId = exact.single().id)
                return cacheResult(cacheKey, exact.single().id)
            }
            if (exact.size > 1) { mapping(CharacterLoadReason.NO_MATCH); return null }
        }

        if (requireExternalId) { mapping(CharacterLoadReason.NO_MATCH); return null }

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
        if (value != null) mappingCache[key] = value
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
    }
}

private fun hasSharedId(a: ExternalAnimeIds, b: ExternalAnimeIds): Boolean =
    (a.shikimoriId != null && b.shikimoriId != null) ||
        (a.malId != null && b.malId != null) || (a.anilistId != null && b.anilistId != null)

private fun hasConflictingIds(a: ExternalAnimeIds, b: ExternalAnimeIds): Boolean =
    (a.shikimoriId != null && b.shikimoriId != null && a.shikimoriId != b.shikimoriId) ||
        (a.malId != null && b.malId != null && a.malId != b.malId) ||
        (a.anilistId != null && b.anilistId != null && a.anilistId != b.anilistId)
