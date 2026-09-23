package com.sergey.animevault.data.metadata

import java.text.Normalizer
import java.util.Locale

enum class AnimeThemeKind {
    OPENING,
    ENDING,
}

data class AnimeThemeSong(
    val kind: AnimeThemeKind,
    val number: Int?,
    val title: String,
    val artist: String?,
    val episodeRange: String?,
    val raw: String,
)

data class AnimeThemeInfo(
    val malId: Long,
    val openings: List<AnimeThemeSong>,
    val endings: List<AnimeThemeSong>,
    val sourceLabel: String = "AnimeThemes",
) {
    val isEmpty: Boolean get() = openings.isEmpty() && endings.isEmpty()
    val totalCount: Int get() = openings.size + endings.size
}

internal data class AnimeThemesSearchCandidate(
    val animeThemesId: Long,
    val titles: List<String>,
    val year: Int?,
    val type: String?,
)

internal fun chooseAnimeThemesCandidate(
    names: List<String>,
    year: Int?,
    type: String?,
    candidates: List<AnimeThemesSearchCandidate>,
): Long? {
    val normalizedNames = names
        .asSequence()
        .map(::normalizeAnimeThemeTitle)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (normalizedNames.isEmpty()) return null

    val normalizedType = normalizeAnimeType(type)
    val best = candidates
        .map { candidate ->
            val candidateTitles = candidate.titles
                .asSequence()
                .map(::normalizeAnimeThemeTitle)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val titleScore = normalizedNames.maxOfOrNull { expected ->
                candidateTitles.maxOfOrNull { actual ->
                    when {
                        expected == actual -> 120
                        expected.length >= 8 && actual.length >= 8 &&
                            (expected.contains(actual) || actual.contains(expected)) -> 72
                        else -> 0
                    }
                } ?: 0
            } ?: 0
            val yearScore = when {
                year == null || candidate.year == null -> 0
                year == candidate.year -> 20
                kotlin.math.abs(year - candidate.year) == 1 -> 5
                else -> -60
            }
            val typeScore = when {
                normalizedType.isBlank() || candidate.type.isNullOrBlank() -> 0
                normalizedType == normalizeAnimeType(candidate.type) -> 8
                else -> 0
            }
            candidate to (titleScore + yearScore + typeScore)
        }
        .maxByOrNull { it.second }
        ?: return null

    return best.first.animeThemesId.takeIf { best.second >= 100 }
}

internal fun normalizeAnimeThemeTitle(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
    return decomposed
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun normalizeAnimeType(value: String?): String = value
    ?.lowercase(Locale.ROOT)
    ?.replace(Regex("[^a-zа-я0-9]+"), "")
    .orEmpty()
