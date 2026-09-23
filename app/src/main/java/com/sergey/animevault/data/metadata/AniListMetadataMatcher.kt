package com.sergey.animevault.data.metadata

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Evidence AnimeVault can safely use when estimating an AniList match. */
data class MetadataMatchEvidence(
    val localTitle: String,
    val onlineAliases: List<String> = emptyList(),
    val onlineTitles: List<String> = emptyList(),
    val episodeTitleHints: List<String> = emptyList(),
    val localEpisodeCount: Int = 0,
    val linkedMalIds: Set<Long> = emptySet(),
)

enum class MetadataMatchConfidence {
    VERIFIED,
    HIGH,
    MEDIUM,
    LOW,
}

data class AniListMetadataMatch(
    val candidate: AniListMetadataCandidate,
    val score: Int,
    val confidence: MetadataMatchConfidence,
    val reasons: List<String>,
    val canAutoApply: Boolean,
)

/**
 * Ranks candidates without making network calls or mutating the library.
 *
 * Deliberately conservative: only an exact external MAL id is allowed to auto-apply.
 * Strong textual matches are surfaced as suggestions and still require confirmation.
 */
fun rankAniListMetadataCandidates(
    candidates: List<AniListMetadataCandidate>,
    evidence: MetadataMatchEvidence,
): List<AniListMetadataMatch> = candidates
    .mapIndexed { index, candidate -> scoreAniListMetadataCandidate(candidate, evidence, index) }
    .sortedWith(
        compareByDescending<AniListMetadataMatch> { it.score }
            .thenBy { it.candidate.canonicalTitle.lowercase(Locale.ROOT) },
    )

fun scoreAniListMetadataCandidate(
    candidate: AniListMetadataCandidate,
    evidence: MetadataMatchEvidence,
    searchRank: Int = 0,
): AniListMetadataMatch {
    val reasons = mutableListOf<String>()
    val malVerified = candidate.malId != null && candidate.malId in evidence.linkedMalIds
    if (malVerified) {
        reasons += "Совпадает MAL ID"
        return AniListMetadataMatch(
            candidate = candidate,
            score = 100,
            confidence = MetadataMatchConfidence.VERIFIED,
            reasons = reasons,
            canAutoApply = true,
        )
    }

    val sourceTitles = buildList {
        evidence.onlineAliases.forEach { add(WeightedTitle(it, 1.00)) }
        evidence.episodeTitleHints.forEach { add(WeightedTitle(it, 0.98)) }
        evidence.onlineTitles.forEach { add(WeightedTitle(it, 0.94)) }
        add(WeightedTitle(evidence.localTitle, 0.90))
    }.filter { normalizeMetadataTitle(it.value).length >= 2 }

    val candidateTitles = buildList {
        add(candidate.canonicalTitle)
        candidate.englishTitle?.let(::add)
        candidate.nativeTitle?.let(::add)
        candidate.synonyms.forEach(::add)
    }.distinct().filter { normalizeMetadataTitle(it).length >= 2 }

    var bestTextScore = 0.0
    var bestSource: String? = null
    var exactTitle = false
    for (source in sourceTitles) {
        for (target in candidateTitles) {
            val raw = titleSimilarity(source.value, target)
            val weighted = raw * source.weight
            if (weighted > bestTextScore) {
                bestTextScore = weighted
                bestSource = source.value
                exactTitle = normalizeMetadataTitle(source.value) == normalizeMetadataTitle(target)
            }
        }
    }

    var score = (bestTextScore * 91.0).toInt().coerceIn(0, 91)
    if (exactTitle && bestTextScore >= 0.88) {
        score = max(score, 88)
        reasons += "Название совпадает"
    } else if (bestTextScore >= 0.82) {
        reasons += "Очень похожее название"
    } else if (bestTextScore >= 0.68) {
        reasons += "Похожее название"
    }

    if (bestSource != null && evidence.onlineAliases.any { normalizeMetadataTitle(it) == normalizeMetadataTitle(bestSource) }) {
        score += 2
        reasons += "Совпадает online alias"
    }

    val localEpisodes = evidence.localEpisodeCount
    val remoteEpisodes = candidate.episodeCount
    if (localEpisodes > 0 && remoteEpisodes != null && remoteEpisodes > 0) {
        val diff = kotlin.math.abs(localEpisodes - remoteEpisodes)
        when {
            diff == 0 -> {
                score += 6
                reasons += "Совпадает число серий"
            }
            diff == 1 -> {
                score += 3
                reasons += "Число серий почти совпадает"
            }
            localEpisodes > remoteEpisodes && diff >= 4 -> score -= 16
            diff >= max(6, remoteEpisodes / 2) -> score -= 12
        }
    }

    // AniList relevance is useful only as a weak tie-breaker, never as proof.
    score += when (searchRank) {
        0 -> 3
        1 -> 2
        2 -> 1
        else -> 0
    }
    score = score.coerceIn(0, 98)

    val confidence = when {
        score >= 88 -> MetadataMatchConfidence.HIGH
        score >= 68 -> MetadataMatchConfidence.MEDIUM
        else -> MetadataMatchConfidence.LOW
    }
    if (reasons.isEmpty() && score >= 55) reasons += "AniList считает результат релевантным"

    return AniListMetadataMatch(
        candidate = candidate,
        score = score,
        confidence = confidence,
        reasons = reasons.distinct().take(3),
        canAutoApply = false,
    )
}

internal fun normalizeMetadataTitle(value: String): String {
    val asciiFolded = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS_REGEX, "")
    return asciiFolded
        .lowercase(Locale.ROOT)
        .replace(SEPARATOR_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
}

private fun titleSimilarity(left: String, right: String): Double {
    val a = normalizeMetadataTitle(left)
    val b = normalizeMetadataTitle(right)
    if (a.isBlank() || b.isBlank()) return 0.0
    if (a == b) return 1.0

    val compactA = a.replace(" ", "")
    val compactB = b.replace(" ", "")
    if (compactA == compactB) return 0.99

    val prefixLike = a.startsWith("$b ") || b.startsWith("$a ")
    val tokenScore = diceTokenSimilarity(a, b)
    val editScore = normalizedEditSimilarity(compactA, compactB)
    val blended = tokenScore * 0.58 + editScore * 0.42
    return if (prefixLike) max(0.91, blended) else blended
}

private fun diceTokenSimilarity(left: String, right: String): Double {
    val a = left.split(' ').filter(String::isNotBlank).toSet()
    val b = right.split(' ').filter(String::isNotBlank).toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val common = a.intersect(b).size
    return (2.0 * common) / (a.size + b.size)
}

private fun normalizedEditSimilarity(left: String, right: String): Double {
    val maxLength = max(left.length, right.length)
    if (maxLength == 0) return 1.0
    return 1.0 - levenshteinDistance(left, right).toDouble() / maxLength.toDouble()
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in left.indices) {
        current[0] = i + 1
        for (j in right.indices) {
            val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}

private data class WeightedTitle(val value: String, val weight: Double)

private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
private val SEPARATOR_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE_REGEX = Regex("\\s+")
