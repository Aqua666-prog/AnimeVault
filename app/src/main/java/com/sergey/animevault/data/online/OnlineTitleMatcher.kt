package com.sergey.animevault.data.online

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative identity matcher used by the virtual multi-source catalogue.
 * External IDs win. Text matching is only a fallback and refuses explicit
 * season/part conflicts to avoid collapsing sequels into one card.
 */
internal object OnlineTitleMatcher {
    fun score(left: OnlineReleaseCard, right: OnlineReleaseCard): Int {
        if (left.providerId == right.providerId && left.id == right.id) return 100

        val idScore = externalIdScore(left.externalIds, right.externalIds)
        if (idScore > 0) return idScore

        if (explicitNumberConflict(left, right, ::extractSeasonNumber)) return 0
        if (explicitNumberConflict(left, right, ::extractPartNumber)) return 0

        val leftNames = normalizedNames(left)
        val rightNames = normalizedNames(right)
        if (leftNames.isEmpty() || rightNames.isEmpty()) return 0

        val exactTitle = leftNames.any(rightNames::contains)
        if (!exactTitle) return 0

        val yearDelta = when {
            left.year == null || right.year == null -> null
            else -> kotlin.math.abs(left.year - right.year)
        }
        return when (yearDelta) {
            null -> 72
            0 -> 86
            1 -> 76
            else -> 48
        }
    }

    fun sameTitle(left: OnlineReleaseCard, right: OnlineReleaseCard): Boolean = score(left, right) >= 72

    fun normalizedNames(card: OnlineReleaseCard): Set<String> = buildSet {
        normalize(card.name).takeIf(String::isNotBlank)?.let(::add)
        normalize(card.englishName.orEmpty()).takeIf(String::isNotBlank)?.let(::add)
        normalize(card.alias).takeIf(String::isNotBlank)?.let(::add)
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(SEASON_WORDS_REGEX, " ")
        .replace(PART_WORDS_REGEX, " ")
        .replace(NON_ALPHANUMERIC_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun externalIdScore(left: ExternalAnimeIds, right: ExternalAnimeIds): Int {
        if (left.shikimoriId != null && left.shikimoriId == right.shikimoriId) return 98
        if (left.malId != null && left.malId == right.malId) return 99
        if (left.anilistId != null && left.anilistId == right.anilistId) return 99
        return 0
    }

    private fun explicitNumberConflict(
        left: OnlineReleaseCard,
        right: OnlineReleaseCard,
        extractor: (OnlineReleaseCard) -> Int?,
    ): Boolean {
        val leftNumber = extractor(left)
        val rightNumber = extractor(right)
        return leftNumber != null && rightNumber != null && leftNumber != rightNumber
    }

    private fun extractSeasonNumber(card: OnlineReleaseCard): Int? = sequenceOf(
        card.name,
        card.englishName,
        card.alias,
        card.season,
    ).filterNotNull().mapNotNull { value ->
        SEASON_CAPTURE_REGEX.find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: ORDINAL_SEASON_CAPTURE_REGEX.find(value)?.groupValues?.get(1)?.toIntOrNull()
    }.firstOrNull()

    private fun extractPartNumber(card: OnlineReleaseCard): Int? = sequenceOf(
        card.name,
        card.englishName,
        card.alias,
    ).filterNotNull().mapNotNull { value ->
        PART_CAPTURE_REGEX.find(value)?.groupValues?.get(1)?.toIntOrNull()
    }.firstOrNull()
}

private val SEASON_WORDS_REGEX = Regex(
    "(?:\\bseason\\s*\\d+\\b|\\bсезон\\s*\\d+\\b|\\b\\d+(?:st|nd|rd|th)\\s+season\\b)",
    RegexOption.IGNORE_CASE,
)
private val PART_WORDS_REGEX = Regex(
    "(?:\\bpart\\s*\\d+\\b|\\bчасть\\s*\\d+\\b)",
    RegexOption.IGNORE_CASE,
)
private val SEASON_CAPTURE_REGEX = Regex("(?:season|сезон)\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val ORDINAL_SEASON_CAPTURE_REGEX = Regex("\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b", RegexOption.IGNORE_CASE)
private val PART_CAPTURE_REGEX = Regex("(?:part|часть)\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE_REGEX = Regex("\\s+")
