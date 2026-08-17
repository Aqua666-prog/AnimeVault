package com.sergey.animevault.data.online

internal data class HtmlStartTag(
    val name: String,
    val attributes: Map<String, String>,
    val raw: String,
)

internal fun htmlStartTags(document: String): List<HtmlStartTag> = START_TAG_REGEX
    .findAll(document)
    .map { match ->
        HtmlStartTag(
            name = match.groupValues[1].lowercase(),
            attributes = parseHtmlAttributes(match.value),
            raw = match.value,
        )
    }
    .toList()

internal fun parseHtmlAttributes(tag: String): Map<String, String> = buildMap {
    QUOTED_ATTRIBUTE_REGEX.findAll(tag).forEach { match ->
        put(match.groupValues[1].lowercase(), decodeHtml(match.groupValues[3]))
    }
    UNQUOTED_ATTRIBUTE_REGEX.findAll(tag).forEach { match ->
        putIfAbsent(match.groupValues[1].lowercase(), decodeHtml(match.groupValues[2]))
    }
}

internal fun htmlText(value: String): String = decodeHtml(
    value
        .replace(SCRIPT_REGEX, " ")
        .replace(STYLE_REGEX, " ")
        .replace(TAG_REGEX, " "),
).replace(Regex("\\s+"), " ").trim()

internal fun decodeHtml(value: String): String {
    var result = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
    result = DECIMAL_ENTITY_REGEX.replace(result) { match ->
        match.groupValues[1].toIntOrNull()?.let(::codePointToString) ?: match.value
    }
    result = HEX_ENTITY_REGEX.replace(result) { match ->
        match.groupValues[1].toIntOrNull(16)?.let(::codePointToString) ?: match.value
    }
    return result
}

internal fun firstTagText(document: String, tagName: String): String? = Regex(
    "<$tagName\\b[^>]*>(.*?)</$tagName>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
).find(document)?.groupValues?.get(1)?.let(::htmlText)?.takeIf(String::isNotBlank)

internal fun metaContent(document: String, vararg names: String): String? {
    val wanted = names.map(String::lowercase).toSet()
    return htmlStartTags(document)
        .asSequence()
        .filter { it.name == "meta" }
        .firstOrNull {
            it.attributes["property"]?.lowercase() in wanted || it.attributes["name"]?.lowercase() in wanted
        }
        ?.attributes
        ?.get("content")
        ?.takeIf(String::isNotBlank)
}

private fun codePointToString(codePoint: Int): String = runCatching {
    String(Character.toChars(codePoint))
}.getOrDefault("")

private val START_TAG_REGEX = Regex(
    "<([a-zA-Z][a-zA-Z0-9:-]*)\\b[^>]*>",
    RegexOption.DOT_MATCHES_ALL,
)
private val QUOTED_ATTRIBUTE_REGEX = Regex(
    "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*([\"'])(.*?)\\2",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private val UNQUOTED_ATTRIBUTE_REGEX = Regex(
    "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*([^\\s\"'=<>`]+)",
    RegexOption.IGNORE_CASE,
)
private val TAG_REGEX = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)
private val SCRIPT_REGEX = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val STYLE_REGEX = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d+);")
private val HEX_ENTITY_REGEX = Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)
