package com.sergey.animevault.data.scanner

import com.sergey.animevault.data.metadata.AniListMetadataCandidate
import com.sergey.animevault.data.metadata.AniListMetadataMatch
import com.sergey.animevault.data.metadata.MetadataMatchConfidence
import com.sergey.animevault.data.metadata.MetadataMatchEvidence
import com.sergey.animevault.data.metadata.normalizeMetadataTitle
import com.sergey.animevault.data.metadata.rankAniListMetadataCandidates

/**
 * Conservative automatic matcher used after an offline scan.
 *
 * It never treats a merely similar AniList search result as proof. Automatic linking requires
 * an exact normalized title/alias match, a strong ranking score and a clear margin over the
 * runner-up. This keeps similarly named seasons/remakes from silently sharing metadata.
 */
object LocalTitleRecognizer {
    const val MIN_AUTO_SCORE = 94
    const val MIN_SCORE_MARGIN = 7

    fun queryFor(title: DiscoveredTitle): String = title.suggestedName
        .replace(SEASON_SUFFIX, " ")
        .replace(RELEASE_NOISE, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .ifBlank { title.suggestedName.trim() }

    fun evidenceFor(title: DiscoveredTitle): MetadataMatchEvidence = MetadataMatchEvidence(
        localTitle = queryFor(title),
        episodeTitleHints = title.episodes
            .asSequence()
            .map { EpisodeNameParser.parse(it.fileName).titleHint.orEmpty() }
            .filter(String::isNotBlank)
            .distinct()
            .take(6)
            .toList(),
        localEpisodeCount = title.episodes.mapNotNull(DiscoveredEpisode::episodeNumber)
            .filter { it > 0.0 }
            .distinct()
            .size
            .takeIf { it > 0 }
            ?: title.episodes.size,
    )

    fun rank(
        title: DiscoveredTitle,
        candidates: List<AniListMetadataCandidate>,
    ): List<AniListMetadataMatch> = rankAniListMetadataCandidates(candidates, evidenceFor(title))

    fun autoCandidate(
        title: DiscoveredTitle,
        candidates: List<AniListMetadataCandidate>,
    ): AniListMetadataCandidate? {
        val ranked = rank(title, candidates)
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.score ?: 0
        val exact = candidateTitles(best.candidate).any { candidateTitle ->
            normalizeMetadataTitle(candidateTitle) == normalizeMetadataTitle(queryFor(title))
        }
        val episodeCompatible = episodeCountCompatible(
            local = evidenceFor(title).localEpisodeCount,
            remote = best.candidate.episodeCount,
        )
        val seasonCompatible = seasonCompatible(title.suggestedName, best.candidate)
        val strong = best.confidence == MetadataMatchConfidence.HIGH ||
            best.confidence == MetadataMatchConfidence.VERIFIED
        return best.candidate.takeIf {
            exact && strong && episodeCompatible && seasonCompatible && best.score >= MIN_AUTO_SCORE &&
                best.score - secondScore >= MIN_SCORE_MARGIN
        }
    }

    private fun candidateTitles(candidate: AniListMetadataCandidate): List<String> = buildList {
        add(candidate.canonicalTitle)
        candidate.englishTitle?.let(::add)
        candidate.nativeTitle?.let(::add)
        addAll(candidate.synonyms)
    }

    private fun seasonCompatible(localTitle: String, candidate: AniListMetadataCandidate): Boolean {
        val localSeason = explicitSeasonNumber(localTitle) ?: return true
        if (localSeason <= 1) return true
        return candidateTitles(candidate).any { remoteTitle ->
            val normalized = normalizeMetadataTitle(remoteTitle)
            Regex("(?i)\\b(?:season|part|cour)\\s*$localSeason\\b").containsMatchIn(normalized) ||
                Regex("(?i)\\b${localSeason}(?:nd|rd|th)\\s+season\\b").containsMatchIn(normalized) ||
                normalized.endsWith(" $localSeason")
        }
    }

    private fun explicitSeasonNumber(value: String): Int? = Regex(
        "(?i)(?:season|сезон|cour|part|часть)\\s*(\\d{1,3})",
    ).find(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun episodeCountCompatible(local: Int, remote: Int?): Boolean {
        if (local <= 0 || remote == null || remote <= 0) return true
        if (local == remote) return true
        // Incomplete downloads are common, but a local collection larger than the declared
        // remote season is a strong sign that this is the wrong season/remake.
        return local < remote && remote - local <= maxOf(3, remote / 3)
    }

    private val SEASON_SUFFIX = Regex(
        "(?i)\\s*[—–-]\\s*(?:season|сезон|cour|part|часть)\\s*\\d{1,3}.*$",
    )
    private val RELEASE_NOISE = Regex(
        "(?i)\\b(?:BDRip|WEB[- .]?DL|WEBRip|BluRay|1080p|720p|2160p|HEVC|x26[45]|AV1)\\b",
    )
    private val WHITESPACE = Regex("\\s+")
}
