package com.sergey.animevault.data.titles

import com.google.gson.Gson

/** Bounds only rebuildable metadata. Room remains authoritative for the library and its IDs. */
internal object AnimeTitleCachePolicy {
    const val MAX_ENTRIES = 4000
    private const val MAX_REFERENCES = 16
    private const val MAX_JSON_CHARS = 8_000_000

    fun restore(json: String?, gson: Gson = Gson()): AnimeTitleIndex = runCatching {
        if (json == null || json.length > MAX_JSON_CHARS) return AnimeTitleIndex()
        val value = gson.fromJson(json, AnimeTitleIndex::class.java) ?: return AnimeTitleIndex()
        // Gson bypasses Kotlin defaults, including non-null fields, on damaged input.
        value.names.values.forEach { it.overlay(AnimeNames()) }
        value.references.values.forEach { refs -> refs.forEach { it.length } }
        bound(value)
    }.getOrElse { AnimeTitleIndex() }

    fun merge(old: AnimeTitleIndex, values: Map<String, AnimeNames>, links: Map<String, List<String>> = emptyMap(),
        liveLocals: Set<Long>? = null): AnimeTitleIndex {
        val liveKeys = liveLocals?.map(::localTitleKey)?.toSet()
        val names = old.names.filterKeys { liveKeys == null || !it.startsWith("local:") || it in liveKeys }.toMutableMap()
        values.forEach { (key, value) ->
            if (key.isNotBlank() && (liveKeys == null || !key.startsWith("local:") || key in liveKeys)) {
                names[key] = if (liveKeys != null && key.startsWith("local:")) value
                    else (names.remove(key) ?: AnimeNames()).overlay(value)
            }
        }
        return bound(AnimeTitleIndex(names, old.references + links))
    }

    fun bound(index: AnimeTitleIndex): AnimeTitleIndex {
        // Local records win over metadata churn. Within each class keep the most recent updates.
        val selected = index.names.entries.toList().asReversed()
            .sortedBy { if (it.key.startsWith("local:")) 0 else if (it.key.startsWith("provider:")) 1 else 2 }
            .take(MAX_ENTRIES).asReversed()
        val names = selected.associate { (key, value) ->
            key to value.copy(russian = clean(value.russian), name = clean(value.name),
                english = clean(value.english), japanese = clean(value.japanese),
                synonyms = value.synonyms.mapNotNull(::clean).distinct().take(32))
        }
        val refs = index.references.filterKeys { it in names }.mapValues { (key, refs) ->
            refs.asSequence().filter { it.isNotBlank() && it.length <= 400 && it != key }
                // External IDs without cached names are valid identity edges. Local IDs need a live record.
                .filter { !it.startsWith("local:") || it in names }.distinct().take(MAX_REFERENCES).toList()
        }.filterValues { it.isNotEmpty() }
        return AnimeTitleIndex(names, refs)
    }

    private fun clean(value: String?): String? = AnimeNames.clean(value)?.takeIf { it.length <= 400 }
}
