package com.sergey.animevault.data.titles

import com.sergey.animevault.data.online.OnlineSearchRanking

data class LocalTitleCandidate(val key: String, val title: String, val score: Int)
/** Bounded immutable alias index. Built off-main only when the metadata snapshot changes. */
class AnimeSearchSuggestions(index: AnimeTitleIndex) {
    private data class Alias(val key: String, val title: String, val normalized: String, val romanized: String)
    private val aliases: List<Alias> = run {
        val entries = index.names.entries.sortedWith(compareBy<Map.Entry<String, AnimeNames>> {
            if (it.key.startsWith("local:")) 0 else if (it.key.startsWith("provider:")) 1 else 2
        }.thenBy { it.key }).take(4000).map { (key, seed) ->
            key to AnimeTitleResolver.aliases(index.namesFor(listOf(key), seed), "").asSequence()
                .map { title ->
                    val normalized = OnlineSearchRanking.normalize(title)
                    Alias(key, title, normalized, transliterate(normalized))
                }.filter { it.normalized.length in 2..80 }.distinctBy { it.normalized }.take(16).toList()
        }
        // Give every known record one alias before spending the remaining global alias budget.
        (0 until 16).asSequence().flatMap { ordinal -> entries.asSequence().mapNotNull { it.second.getOrNull(ordinal) } }
            .take(12_000).toList()
    }
    private val exact = aliases.groupBy { it.normalized }
    private val roman = aliases.groupBy { it.romanized }
    private val prefix = aliases.groupBy { it.normalized.take(2) }

    // At most 12,000 aliases * 79 distinct bigrams = 948,000 integer postings.
    // IntArray buckets avoid one boxed object per posting. No unbounded deletion strings.
    private val bigrams: Map<String, IntArray> = buildMap<String, MutableList<Int>> {
        aliases.forEachIndexed { id, alias ->
            alias.normalized.windowed(2).distinct().forEach { gram -> getOrPut(gram) { mutableListOf() }.add(id) }
        }
    }.mapValues { (_, ids) -> ids.toIntArray() }
    internal val fuzzyPostingCount: Int get() = bigrams.values.sumOf { it.size }
    internal val aliasCount: Int get() = aliases.size

    private fun candidates(needle: String): List<Alias> {
        val fuzzy = if (needle.length in 5..80) {
            val counts = IntArray(aliases.size)
            needle.windowed(2).distinct().forEach { gram ->
                bigrams[gram]?.forEach { id ->
                    if (kotlin.math.abs(aliases[id].normalized.length - needle.length) <= 1) counts[id]++
                }
            }
            aliases.indices.asSequence().filter { counts[it] > 0 }
                .sortedWith(compareByDescending<Int> { counts[it] }.thenBy { it })
                .take(100).map { aliases[it] }.toList()
        } else emptyList()
        // Reserve space for fuzzy recall even for a very popular prefix. Exact always ranks first.
        return (exact[needle].orEmpty().take(100) + roman[transliterate(needle)].orEmpty().take(50) +
            prefix[needle.take(2)].orEmpty().take(50) + fuzzy).distinct().take(MAX_CANDIDATES)
    }
    internal fun candidateCount(query: String): Int = candidates(OnlineSearchRanking.normalize(query)).size

    fun search(query: String): List<LocalTitleCandidate> {
        val needle = OnlineSearchRanking.normalize(query)
        if (needle.length !in 2..80) return emptyList()
        val romanNeedle = transliterate(needle)
        return candidates(needle).mapNotNull { alias ->
            val score = when {
                alias.normalized == needle -> 100
                alias.romanized == romanNeedle -> 85
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
        internal const val MAX_CANDIDATES = 300
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
