package com.sergey.animevault.data.titles

import com.sergey.animevault.data.online.OnlineSearchRanking

data class LocalTitleCandidate(val key: String, val title: String, val score: Int)
/** Bounded immutable alias index. Built off-main only when the metadata snapshot changes. */
class AnimeSearchSuggestions(index: AnimeTitleIndex) {
    private data class Alias(val key: String, val title: String, val normalized: String, val romanized: String)
    private val aliases = index.names.entries.asSequence().take(4000).flatMap { (key, seed) ->
        val names = index.namesFor(listOf(key), seed)
        AnimeTitleResolver.aliases(names, "").asSequence().take(16).map { key to it }
    }.take(12_000).map { (key, title) ->
        val normalized = OnlineSearchRanking.normalize(title)
        Alias(key, title, normalized, transliterate(normalized))
    }.filter { it.normalized.length in 2..80 }.toList()
    private val exact = aliases.groupBy { it.normalized }
    private val roman = aliases.groupBy { it.romanized }
    private val prefix = aliases.groupBy { it.normalized.take(2) }

    fun search(query: String): List<LocalTitleCandidate> {
        val needle = OnlineSearchRanking.normalize(query)
        if (needle.length !in 2..80) return emptyList()
        val candidates = (exact[needle].orEmpty() + roman[transliterate(needle)].orEmpty() +
            prefix[needle.take(2)].orEmpty().take(200)).distinct().take(300)
        return candidates.mapNotNull { alias ->
            val score = when {
                alias.normalized == needle -> 100
                alias.romanized == transliterate(needle) -> 85
                alias.normalized.startsWith(needle) -> 75
                needle.length >= 5 && oneEdit(needle, alias.normalized) -> 30
                else -> 0
            }
            if (score > 0) LocalTitleCandidate(alias.key, alias.title, score) else null
        }.sortedWith(compareByDescending<LocalTitleCandidate> { it.score }.thenBy { it.key }.thenBy { it.title })
            .distinctBy { it.key }.take(8)
    }
    fun suggest(query: String): List<String> = search(query).map { it.title }.distinct().take(3)
    companion object {
        fun transliterate(text: String): String = buildString {
            text.forEach { append(cyrillic[it] ?: it.toString()) }
        }
        private val cyrillic = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList().zip(
            listOf("a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","shch","","y","","e","yu","ya")
        ).toMap()
        private fun oneEdit(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0; var j = 0; var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
            }
            return edits + a.length - i + b.length - j <= 1
        }
    }
}
