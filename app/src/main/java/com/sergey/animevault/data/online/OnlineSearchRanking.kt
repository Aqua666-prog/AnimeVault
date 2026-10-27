package com.sergey.animevault.data.online

import com.sergey.animevault.data.titles.AnimeTitleResolver
import java.text.Normalizer
import java.util.Locale

/** Pure ranking of already returned results. Never participates in identity matching or networking. */
internal object OnlineSearchRanking {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun rank(cards: List<OnlineReleaseCard>, query: String, contextScore: (OnlineReleaseCard) -> Int = { 0 }): List<OnlineReleaseCard> {
        if (query.isBlank()) return cards
        val needle = normalize(query)
        if (needle.isEmpty()) return cards
        // Fuzzy work is bounded independently of the size of the loaded catalogue.
        val fuzzyCandidates = cards.sortedWith(compareBy<OnlineReleaseCard> { it.providerId }.thenBy { it.id })
            .take(200).map { it.providerId to it.id }.toSet()
        return cards.sortedWith(compareByDescending<OnlineReleaseCard> {
            score(it, query, needle, (it.providerId to it.id) in fuzzyCandidates)
        }.thenByDescending(contextScore).thenBy { it.providerId }.thenBy { it.id })
    }

    internal fun score(card: OnlineReleaseCard, query: String, needle: String = normalize(query), fuzzy: Boolean = true): Int {
        if (needle.isEmpty()) return 0
        val aliases = AnimeTitleResolver.aliases(card.titleNames, card.name) + listOfNotNull(card.englishName, card.alias)
        if (card.name.trim().equals(query.trim(), true)) return 100
        if (aliases.any { it.trim().equals(query.trim(), true) }) return 95
        val names = aliases.map(::normalize).filter { it.isNotEmpty() }
        if (needle in names) return 90
        if (names.any { it.startsWith(needle) }) return 75
        val words = needle.split(' ')
        if (names.any { name -> words.all { it in name.split(' ') } }) return 65
        if (names.any { needle in it }) return 45
        if (fuzzy && needle.length in 5..80 && names.take(16).any { oneEditApart(needle, it) }) return 30
        return 0
    }

    // A single insertion/deletion/substitution, linear memory and bounded input length.
    private fun oneEditApart(left: String, right: String): Boolean {
        if (kotlin.math.abs(left.length - right.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < left.length && j < right.length) {
            if (left[i] == right[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                left.length > right.length -> i++
                right.length > left.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (left.length - i) + (right.length - j) <= 1
    }
}
