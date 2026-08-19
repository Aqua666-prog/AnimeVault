package com.sergey.animevault.data.scanner

import java.util.Locale

data class ParsedEpisodeName(
    val titleHint: String?,
    val episodeNumber: Double?,
    val seasonNumber: Int?,
)

object EpisodeNameParser {
    private val seasonEpisode = Regex(
        pattern = "(?i)\\bS(\\d{1,3})[ ._-]*E(\\d{1,4}(?:\\.\\d+)?)\\b",
    )
    private val namedEpisode = Regex(
        pattern = "\\b(?:episode|ep|серия|эпизод)[ ._#-]*(\\d{1,4}(?:\\.\\d+)?)\\b",
        option = RegexOption.IGNORE_CASE,
    )
    private val shortNamedEpisode = Regex(
        pattern = "(?i)\\bE[ ._#-]*(\\d{1,4}(?:\\.\\d+)?)\\b",
    )
    private val seasonByCross = Regex(
        pattern = "(?i)\\b(\\d{1,3})[xх](\\d{1,4}(?:\\.\\d+)?)\\b",
    )
    private val russianTrailingEpisode = Regex(
        pattern = "\\b(\\d{1,4}(?:\\.\\d+)?)[ ._-]*(?:серия|эпизод)\\b",
        option = RegexOption.IGNORE_CASE,
    )
    private val separatedEpisode = Regex(
        pattern = "\\s[-–—]\\s*(\\d{1,4}(?:\\.\\d+)?)\\b",
    )
    private val bracketedEpisode = Regex(
        pattern = "\\[(\\d{1,4}(?:\\.\\d+)?)(?:v\\d+)?\\]",
        option = RegexOption.IGNORE_CASE,
    )
    private val rangedEpisode = Regex(
        pattern = "\\s[-–—]\\s*(\\d{1,4}(?:\\.\\d+)?)[-~](?:\\d{1,4}(?:\\.\\d+)?)\\b",
    )
    private val trailingBareEpisode = Regex(
        pattern = """(?i)(?:^|\s)(\d{1,4}(?:\.\d+)?)(?:v\d+)?\s*$""",
    )
    private val leadingEpisode = Regex(
        pattern = "^\\s*(\\d{1,4}(?:\\.\\d+)?)(?:\\s|[._-])",
    )
    private val numericOnly = Regex(
        pattern = "^\\s*(\\d{1,4}(?:\\.\\d+)?)\\s*$",
    )
    private val leadingReleaseTag = Regex("^\\s*\\[[^]]+]\\s*")
    private val trailingTag = Regex(
        pattern = """\s*(?:\[[^\]]*(?:1080|720|2160|HEVC|AV1|WEB|BD|x26|AAC)[^\]]*\]|\([^)]*(?:1080|720|2160|HEVC|AV1|WEB|BD|x26|AAC)[^)]*\))\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val separators = Regex("[._]+")
    private val repeatedWhitespace = Regex("\\s+")

    fun parse(fileName: String): ParsedEpisodeName {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val untagged = stripReleaseTags(baseName)

        seasonEpisode.find(untagged)?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[2].toDoubleOrNull(),
                seasonNumber = match.groupValues[1].toIntOrNull(),
            )
        }

        seasonByCross.find(untagged)?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[2].toDoubleOrNull(),
                seasonNumber = match.groupValues[1].toIntOrNull(),
            )
        }

        namedEpisode.find(untagged)?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }


        shortNamedEpisode.find(untagged)?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }

        russianTrailingEpisode.find(untagged)?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }

        rangedEpisode.findAll(untagged).lastOrNull()?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }

        separatedEpisode.findAll(untagged).lastOrNull()?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }

        bracketedEpisode.findAll(untagged).lastOrNull()?.let { match ->
            return ParsedEpisodeName(
                titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = findSeason(untagged),
            )
        }

        trailingBareEpisode.find(untagged)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull()
            // Values that look like video resolution are release metadata, not episode ordinals.
            if (number != null && number < 300) {
                return ParsedEpisodeName(
                    titleHint = cleanTitle(untagged.substring(0, match.range.first)),
                    episodeNumber = number,
                    seasonNumber = findSeason(untagged),
                )
            }
        }

        (numericOnly.find(untagged) ?: leadingEpisode.find(untagged))?.let { match ->
            return ParsedEpisodeName(
                titleHint = null,
                episodeNumber = match.groupValues[1].toDoubleOrNull(),
                seasonNumber = null,
            )
        }

        return ParsedEpisodeName(
            titleHint = cleanTitle(untagged),
            episodeNumber = null,
            seasonNumber = findSeason(untagged),
        )
    }

    fun normalizedStem(fileName: String): String = fileName
        .substringBeforeLast('.', fileName)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\[[^]]+]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(repeatedWhitespace, " ")

    private fun stripReleaseTags(value: String): String {
        var result = value
        while (leadingReleaseTag.containsMatchIn(result)) {
            result = result.replaceFirst(leadingReleaseTag, "")
        }
        while (trailingTag.containsMatchIn(result)) {
            result = result.replaceFirst(trailingTag, "")
        }
        return result.trim()
    }

    private fun cleanTitle(value: String): String? {
        val cleaned = value
            .replace(separators, " ")
            .trim(' ', '-', '–', '—', '_', '.')
            .replace(repeatedWhitespace, " ")
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun findSeason(value: String): Int? = Regex(
        pattern = "\\b(?:season|сезон|часть|part|cour|S)\\s*[._-]*(\\d{1,3})\\b",
        option = RegexOption.IGNORE_CASE,
    ).find(value)?.groupValues?.get(1)?.toIntOrNull()
}
