package com.sergey.animevault.ui.library

/** UTF-8 with BOM and semicolon separator for common Russian spreadsheet locales. */
fun libraryItemsToCsv(items: List<LibraryHubItem>): String = buildString {
    append('\uFEFF')
    append("Название;Источник;Статус;Моя оценка (1-5);Избранное;Заметка\r\n")
    items.forEach { item ->
        val source = item.providerId ?: "Локальный файл"
        val values = listOf(item.title, source, item.list.title,
            item.rating?.toString().orEmpty(), if (item.favorite) "Да" else "Нет", item.note)
        append(values.joinToString(";") { csvSafeValue(it) })
        append("\r\n")
    }
}

private fun csvSafeValue(raw: String): String {
    // Prevent spreadsheet formula injection, including leading whitespace before a formula.
    val dangerous = raw.trimStart().startsWith('=') || raw.trimStart().startsWith('+') ||
        raw.trimStart().startsWith('-') || raw.trimStart().startsWith('@')
    val safe = if (dangerous) "'" + raw else raw
    return "\"" + safe.replace("\"", "\"\"") + "\""
}
