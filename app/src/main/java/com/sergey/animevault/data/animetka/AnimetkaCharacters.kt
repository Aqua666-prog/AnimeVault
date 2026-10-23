package com.sergey.animevault.data.animetka

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sergey.animevault.data.metadata.ShikimoriCharacter
import com.sergey.animevault.data.metadata.sanitizeShikimoriDescription
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** IDs on this endpoint belong to Shikimori; MAL IDs are optional independent metadata. */
internal fun parseAnimetkaCharacters(root: JsonElement): List<ShikimoriCharacter> {
    check(root.isJsonArray) { "Аниметка не вернула список персонажей" }
    return root.asJsonArray.map(::parseAnimetkaCharacter).distinctBy(ShikimoriCharacter::id)
}

internal fun parseAnimetkaCharacter(root: JsonElement): ShikimoriCharacter {
    check(root.isJsonObject) { "Некорректная карточка персонажа Аниметки" }
    val obj = root.asJsonObject
    val id = checkNotNull(obj.text("character_shikimori_id")?.toLongOrNull()?.takeIf { it > 0 }) {
        "Нет Shikimori ID персонажа Аниметки"
    }
    val name = checkNotNull(obj.text("name") ?: obj.text("russian")) { "Нет имени персонажа Аниметки" }
    return ShikimoriCharacter(
        id = id.toString(),
        malId = obj.text("mal_id")?.toLongOrNull()?.takeIf { it > 0 },
        name = name,
        russianName = obj.text("russian"),
        japaneseName = obj.text("japanese"),
        synonyms = obj.strings("synonyms"),
        imageUrl = normalizeCharacterPosterUrl(obj.text("poster_url")),
        roleRu = obj.strings("roles_ru").firstOrNull(),
        roleEn = obj.strings("roles_en").firstOrNull(),
        description = sanitizeShikimoriDescription(obj.text("description")),
        source = "Аниметка",
    )
}

/** Keep the actual image host: metadata availability says nothing about shikimori.io. */
internal fun normalizeCharacterPosterUrl(value: String?): String? {
    val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val absolute = when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> "https://shikimori.io$clean"
        else -> clean
    }
    val url = absolute.toHttpUrlOrNull() ?: return null
    return url.newBuilder().scheme("https").build().toString()
}

private fun JsonObject.text(key: String): String? = get(key)
    ?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
    ?.asString?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.strings(key: String): List<String> = get(key)
    ?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull {
        it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()
