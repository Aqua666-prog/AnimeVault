package com.sergey.animevault.data.scanner

import java.util.Locale

object GroupingPolicy {
    private val seasonDirectory = Regex(
        pattern = "(?i)^(?:season|сезон|cour|part|часть|tv)[ ._-]*\\d+.*$",
    )
    private val episodeDirectory = Regex(
        pattern = "(?i)^(?:(?:episode|ep|серия|эпизод)[ ._#-]*\\d{1,4}(?:\\.\\d+)?|\\d{3,4}(?:[ ._-]+\\d{1,4})?)$",
    )
    private val russianNamedSeason = Regex(
        pattern = "(?i)^(.+?)\\s*\\(\\s*(первый|второй|третий|четв[её]ртый|пятый|шестой|седьмой|восьмой|девятый|десятый|\\d+(?:-й)?)\\s+сезон\\s*\\)(?:\\s*[-–—].*)?$",
    )
    private val russianNumericSeason = Regex(
        pattern = "(?i)^(.+?)\\s*[-–—]?\\s*(\\d+)(?:-й)?\\s+сезон(?:\\s*[-–—].*)?$",
    )

    fun chooseTitle(
        rootName: String,
        relativeDirectories: List<String>,
        parsedTitleHint: String?,
        parsedSeasonNumber: Int? = null,
    ): String {
        val firstDirectory = relativeDirectories.firstOrNull()
        val secondDirectory = relativeDirectories.getOrNull(1)
        val rawTitle = when {
            firstDirectory == null -> withParsedSeason(parsedTitleHint ?: rootName, parsedSeasonNumber)
            episodeDirectory.matches(firstDirectory.trim()) -> rootName
            seasonDirectory.matches(firstDirectory.trim()) -> "$rootName — $firstDirectory"
            secondDirectory != null && seasonDirectory.matches(secondDirectory.trim()) -> {
                "$firstDirectory — $secondDirectory"
            }
            else -> withParsedSeason(firstDirectory, parsedSeasonNumber)
        }
        return normalizeTitle(rawTitle)
    }

    private fun withParsedSeason(title: String, seasonNumber: Int?): String =
        if (seasonNumber == null || seasonDirectory.matches(title.trim())) {
            title
        } else {
            "$title — сезон $seasonNumber"
        }

    fun titleDirectoryKey(relativeDirectories: List<String>): String {
        val firstDirectory = relativeDirectories.firstOrNull() ?: return ""
        val secondDirectory = relativeDirectories.getOrNull(1)
        val titleDirectories = when {
            episodeDirectory.matches(firstDirectory.trim()) -> emptyList()
            seasonDirectory.matches(firstDirectory.trim()) -> listOf(firstDirectory)
            secondDirectory != null && seasonDirectory.matches(secondDirectory.trim()) -> {
                listOf(firstDirectory, secondDirectory)
            }
            else -> listOf(firstDirectory)
        }
        return titleDirectories.joinToString("/")
    }

    fun normalizeTitle(value: String): String {
        val cleaned = value
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        russianNamedSeason.matchEntire(cleaned)?.let { match ->
            val number = seasonWordToNumber(match.groupValues[2])
            return "${match.groupValues[1].trim()} — сезон $number"
        }
        russianNumericSeason.matchEntire(cleaned)?.let { match ->
            return "${match.groupValues[1].trim()} — сезон ${match.groupValues[2]}"
        }
        return cleaned.ifEmpty { "Без названия" }
    }

    fun keyFor(title: String): String = title
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun seasonWordToNumber(value: String): String = when (
        value.lowercase(Locale.ROOT).removeSuffix("-й")
    ) {
        "первый" -> "1"
        "второй" -> "2"
        "третий" -> "3"
        "четвертый", "четвёртый" -> "4"
        "пятый" -> "5"
        "шестой" -> "6"
        "седьмой" -> "7"
        "восьмой" -> "8"
        "девятый" -> "9"
        "десятый" -> "10"
        else -> value.filter(Char::isDigit).ifEmpty { value }
    }
}
