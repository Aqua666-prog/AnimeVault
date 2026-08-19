package com.sergey.animevault.ui.preferences

internal fun mergeSearchHistory(
    existing: List<String>,
    query: String,
    maxItems: Int = 8,
): List<String> {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    if (normalized.length < 2 || maxItems <= 0) return existing.take(maxItems.coerceAtLeast(0))
    return buildList<String> {
        add(normalized)
        existing.forEach { item ->
            val cleaned = item.trim().replace(Regex("\\s+"), " ")
            if (cleaned.isNotEmpty() && !cleaned.equals(normalized, ignoreCase = true) &&
                none { it.equals(cleaned, ignoreCase = true) }
            ) {
                add(cleaned)
            }
        }
    }.take(maxItems)
}

internal fun removeSearchHistoryItem(existing: List<String>, query: String): List<String> =
    existing.filterNot { it.equals(query.trim(), ignoreCase = true) }
