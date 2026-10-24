package com.sergey.animevault.data.titles

import java.text.Normalizer
import java.util.Locale

enum class AnimeTitleLanguage {
    RUSSIAN, INTERNATIONAL, JAPANESE;
    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: RUSSIAN
    }
}

/** Source values, never a translated display string or an identity. */
data class AnimeNames(
    val russian: String? = null,
    val name: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String> = emptyList(),
) {
    fun overlay(other: AnimeNames) = AnimeNames(
        clean(other.russian) ?: russian, clean(other.name) ?: name,
        clean(other.english) ?: english, clean(other.japanese) ?: japanese,
        (synonyms + other.synonyms).mapNotNull(::clean).distinct(),
    )
    companion object {
        fun clean(value: String?): String? = value?.trim()?.takeUnless { it.isEmpty() || it.equals("null", true) }
        /** Script classification is only a fallback for unlabelled provider originals. No translation. */
        fun original(value: String, alternate: String? = null): AnimeNames = AnimeNames(
            russian = value.takeIf { text -> text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } },
            name = clean(alternate) ?: value, synonyms = listOfNotNull(value, alternate),
        )
    }
}

object AnimeTitleResolver {
    fun resolve(names: AnimeNames, original: String, language: AnimeTitleLanguage): String {
        val ordered = when (language) {
            AnimeTitleLanguage.RUSSIAN -> listOf(names.russian, names.english, names.name, names.japanese)
            AnimeTitleLanguage.INTERNATIONAL -> listOf(names.english, names.name, names.russian, names.japanese)
            AnimeTitleLanguage.JAPANESE -> listOf(names.japanese, names.name, names.english, names.russian)
        }
        return (ordered + original + names.synonyms).firstNotNullOfOrNull { AnimeNames.clean(it) }.orEmpty()
    }
    fun aliases(names: AnimeNames, original: String): List<String> =
        (listOf(original, names.russian, names.name, names.english, names.japanese) + names.synonyms)
            .mapNotNull { AnimeNames.clean(it) }.distinct()

    fun matches(names: AnimeNames, original: String, query: String): Boolean {
        val needle = normalize(query)
        return needle.isEmpty() || aliases(names, original).any { normalize(it).contains(needle) }
    }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}

/** Keys are namespaced. AniList, MAL and Shikimori numbers are never interchanged. */
fun providerTitleKey(provider: String, id: String) = "provider:$provider:$id"
fun localTitleKey(id: Long) = "local:$id"
fun animeIdKey(source: String, id: Long?) = id?.takeIf { it > 0 }?.let { "$source:$it" }

data class AnimeTitleIndex(
    val names: Map<String, AnimeNames> = emptyMap(),
    val references: Map<String, List<String>> = emptyMap(),
) {

    /** Alternate provider queries are taken only from exact aliases already cached by ID. */
    fun searchQueries(query: String): List<String> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        return names.values.asSequence()
            .filter { AnimeTitleResolver.aliases(it, "").any { alias -> alias.equals(needle, true) } }
            .flatMap { AnimeTitleResolver.aliases(it, "").asSequence() }
            .filter { it.length >= 2 && !it.equals(needle, true) }
            .distinct().take(2).toList()
    }
    fun namesFor(keys: List<String>, seed: AnimeNames = AnimeNames()): AnimeNames {
        var result = seed
        // Only explicit ID edges, no transitive traversal or title-based merging.
        for (key in keys) {
            names[key]?.let { result = result.overlay(it) }
            for (reference in references[key].orEmpty()) names[reference]?.let { result = result.overlay(it) }
        }
        return result
    }
}
