package com.sergey.animevault.data.animetka

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sergey.animevault.data.online.ExternalAnimeIds

internal data class AnimetkaCatalogEnvelope(
    val items: List<AnimetkaAnimeDto>,
    val total: Int? = null,
)

internal data class AnimetkaAnimeDto(
    val id: String,
    val title: String,
    val animeTitle: String? = null,
    val titleEn: String? = null,
    val titleOriginal: String? = null,
    val posterUrl: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val status: String? = null,
    val season: String? = null,
    val description: String? = null,
    val episodeCount: Int? = null,
    val genres: List<String> = emptyList(),
    val externalIds: ExternalAnimeIds = ExternalAnimeIds(),
)

internal data class AnimetkaTranslationDto(
    val id: String,
    val name: String,
    val subtitles: Boolean,
    val episodeCount: Int?,
    val quality: Int?,
    val selected: Boolean,
)

internal data class AnimetkaSkipRangeDto(
    val startSeconds: Double,
    val stopSeconds: Double,
)

internal data class AnimetkaSkipDataDto(
    val opening: AnimetkaSkipRangeDto? = null,
    val ending: AnimetkaSkipRangeDto? = null,
)

internal data class AnimetkaEpisodeDto(
    val id: String,
    val title: String?,
    val file: String?,
    val previewUrl: String?,
    val durationSeconds: Double?,
    val skipData: AnimetkaSkipDataDto?,
)

internal data class AnimetkaPlaylistDto(
    val translations: List<AnimetkaTranslationDto>,
    val episodes: List<AnimetkaEpisodeDto>,
    val selectedTranslationId: String?,
)

internal data class AnimetkaTrailerDto(
    val materialId: String,
    val number: Int,
    val url: String,
    val previewUrl: String?,
)

internal object AnimetkaJson {
    fun parseCatalog(root: JsonElement): AnimetkaCatalogEnvelope {
        val payload = root.unwrapObjectPayload()
        val items = when {
            root.isJsonArray -> root.asJsonArray
            payload != null -> payload.firstArray("items", "results", "anime", "list", "data")
                ?: payload.takeIf { it.looksLikeAnimeObject() }?.let { JsonArray().apply { add(it) } }
            else -> null
        }.orEmptyArray()
        val container = root.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val total = container?.firstInt("total", "count", "total_count", "totalCount")
            ?: payload?.firstInt("total", "count", "total_count", "totalCount")
        return AnimetkaCatalogEnvelope(
            items = items.mapNotNull(::parseAnime),
            total = total?.takeIf { it >= 0 },
        )
    }

    fun parseAnime(root: JsonElement): AnimetkaAnimeDto? {
        val obj = root.unwrapObjectPayload() ?: return null
        val id = obj.firstString("id", "material_id", "animetka_id")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val title = obj.firstString("title", "anime_title", "name", "title_ru")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "Аниметка #$id"
        return AnimetkaAnimeDto(
            id = id,
            title = title,
            animeTitle = obj.firstString("anime_title", "name", "title_ru")?.cleanText(),
            titleEn = obj.firstString("title_en", "english_title", "title_english")?.cleanText(),
            titleOriginal = obj.firstString("title_orig", "title_original", "original_title")?.cleanText(),
            posterUrl = obj.firstImageUrl("poster", "poster_url", "image", "image_url", "poster_main", "cover"),
            year = obj.firstInt("year", "aired_year", "release_year"),
            type = obj.firstString("type", "kind", "format")?.cleanText(),
            status = obj.firstString("status", "release_status")?.cleanText(),
            season = obj.firstString("season", "season_name")?.cleanText(),
            description = obj.firstString("description", "description_html", "synopsis")?.cleanText(),
            episodeCount = obj.firstInt("episodes", "episodes_total", "episode_count", "episodes_count"),
            genres = obj.firstGenres("genres", "genre"),
            externalIds = ExternalAnimeIds(
                shikimoriId = obj.firstLong("shikimori_id", "shikimoriId"),
                malId = obj.firstLong("mal_id", "myanimelist_id", "malId"),
                anilistId = obj.firstLong("anilist_id", "anilistId"),
            ),
        )
    }

    fun parsePlaylist(root: JsonElement): AnimetkaPlaylistDto {
        val obj = root.unwrapObjectPayload() ?: JsonObject()
        val translations = obj.firstArray("translations", "translation")
            .orEmptyArray()
            .mapNotNull(::parseTranslation)
            .distinctBy(AnimetkaTranslationDto::id)
        val selectedFromRoot = obj.firstString(
            "selected_translation", "selected_translation_id", "translation_id", "tid", "selected_tid",
        )?.trim()?.takeIf(String::isNotBlank)
        val selected = translations.firstOrNull(AnimetkaTranslationDto::selected)?.id ?: selectedFromRoot
        val episodeElement = obj.firstElement("list", "episodes", "playlist")
        val episodes = when {
            episodeElement == null || episodeElement.isJsonNull -> emptyList()
            episodeElement.isJsonArray -> episodeElement.asJsonArray.mapIndexedNotNull { index, item ->
                parseEpisode(item, fallbackId = "s${index + 1}")
            }
            episodeElement.isJsonObject -> episodeElement.asJsonObject.entrySet().mapNotNull { (key, value) ->
                parseEpisode(value, fallbackId = key)
            }
            else -> emptyList()
        }
        return AnimetkaPlaylistDto(
            translations = translations,
            episodes = episodes,
            selectedTranslationId = selected,
        )
    }

    fun parseTrailers(root: JsonElement, releaseId: String): List<AnimetkaTrailerDto> {
        val obj = root.unwrapObjectPayload()
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            obj != null -> obj.firstArray("trailers", "items", "data", "list")
            else -> null
        }.orEmptyArray()
        return arr.mapIndexedNotNull { index, item ->
            val trailer = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapIndexedNotNull null
            val number = trailer.firstInt("num", "number", "id") ?: (index + 1)
            if (number <= 0) return@mapIndexedNotNull null
            val url = trailer.firstString("url", "video_url", "video")
                ?.cleanText()
                ?.takeIf(String::isNotBlank)
                ?: "/api/anime/$releaseId/trailers/$number"
            AnimetkaTrailerDto(
                materialId = releaseId,
                number = number,
                url = url,
                previewUrl = trailer.firstString("preview_url", "preview", "poster")?.cleanText(),
            )
        }.distinctBy(AnimetkaTrailerDto::number)
    }

    private fun parseTranslation(element: JsonElement): AnimetkaTranslationDto? {
        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val id = obj.firstString("value", "id", "tid", "translation_id")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val name = obj.firstString("name", "title", "label")
            ?.cleanText()
            ?.takeIf(String::isNotBlank)
            ?: "Перевод $id"
        val subtitles = obj.firstBoolean("subs", "subtitles", "is_subs") ?: run {
            val marker = name.lowercase()
            "субтит" in marker || "subtitle" in marker || "sub" == marker
        }
        val quality = obj.firstInt("quality", "max_quality")
            ?: obj.firstString("quality", "max_quality")?.extractQuality()
        return AnimetkaTranslationDto(
            id = id,
            name = name,
            subtitles = subtitles,
            episodeCount = obj.firstInt("episode_count", "episodes", "episodes_count"),
            quality = quality,
            selected = obj.firstBoolean("selected", "active", "current", "is_selected") == true,
        )
    }

    private fun parseEpisode(element: JsonElement, fallbackId: String): AnimetkaEpisodeDto? {
        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val id = obj.firstString("id", "episode_id", "key")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: fallbackId.takeIf(String::isNotBlank)
            ?: return null
        val skip = obj.firstObject("skipdata", "skip_data", "skips")?.let(::parseSkipData)
        return AnimetkaEpisodeDto(
            id = id,
            title = obj.firstString("title", "name")?.cleanText(),
            file = obj.firstString("file", "files", "stream")?.trim()?.takeIf(String::isNotBlank),
            previewUrl = obj.firstString("preview", "preview_url", "poster")?.cleanText(),
            durationSeconds = obj.firstDouble("duration", "duration_seconds"),
            skipData = skip,
        )
    }

    private fun parseSkipData(obj: JsonObject): AnimetkaSkipDataDto = AnimetkaSkipDataDto(
        opening = obj.firstObject("opening", "op")?.let(::parseSkipRange),
        ending = obj.firstObject("ending", "ed")?.let(::parseSkipRange),
    )

    private fun parseSkipRange(obj: JsonObject): AnimetkaSkipRangeDto? {
        val start = obj.firstDouble("start", "from") ?: return null
        val stop = obj.firstDouble("stop", "end", "to") ?: return null
        if (!start.isFinite() || !stop.isFinite() || start < 0.0 || stop <= start) return null
        return AnimetkaSkipRangeDto(startSeconds = start, stopSeconds = stop)
    }
}

private fun JsonElement.unwrapObjectPayload(): JsonObject? {
    if (!isJsonObject) return null
    val root = asJsonObject
    for (key in listOf("data", "result", "anime", "material")) {
        val nested = root.get(key)
        if (nested != null && nested.isJsonObject) return nested.asJsonObject
    }
    return root
}

private fun JsonObject.looksLikeAnimeObject(): Boolean = has("id") && (
    has("title") || has("anime_title") || has("name")
)

private fun JsonObject.firstElement(vararg names: String): JsonElement? = names.asSequence()
    .mapNotNull { name -> get(name)?.takeUnless(JsonElement::isJsonNull) }
    .firstOrNull()

private fun JsonObject.firstArray(vararg names: String): JsonArray? = names.asSequence()
    .mapNotNull { name -> get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray }
    .firstOrNull()

private fun JsonObject.firstObject(vararg names: String): JsonObject? = names.asSequence()
    .mapNotNull { name -> get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    .firstOrNull()

private fun JsonObject.firstString(vararg names: String): String? = names.asSequence()
    .mapNotNull { name -> get(name)?.primitiveString() }
    .firstOrNull()

private fun JsonObject.firstLong(vararg names: String): Long? = names.asSequence()
    .mapNotNull { name -> get(name)?.primitiveLong() }
    .firstOrNull()

private fun JsonObject.firstInt(vararg names: String): Int? = firstLong(*names)
    ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
    ?.toInt()

private fun JsonObject.firstDouble(vararg names: String): Double? = names.asSequence()
    .mapNotNull { name -> get(name)?.primitiveDouble() }
    .firstOrNull()

private fun JsonObject.firstBoolean(vararg names: String): Boolean? = names.asSequence()
    .mapNotNull { name -> get(name)?.primitiveBoolean() }
    .firstOrNull()

private fun JsonObject.firstImageUrl(vararg names: String): String? = names.asSequence().mapNotNull { name ->
    val element = get(name) ?: return@mapNotNull null
    when {
        element.isJsonPrimitive -> element.primitiveString()?.cleanText()
        element.isJsonObject -> element.asJsonObject.firstString(
            "url", "original", "full", "main", "preview", "src", "x225", "x350",
        )?.cleanText()
        else -> null
    }
}.firstOrNull { !it.isNullOrBlank() }

private fun JsonObject.firstGenres(vararg names: String): List<String> {
    val element = names.asSequence().mapNotNull { get(it)?.takeUnless(JsonElement::isJsonNull) }.firstOrNull()
        ?: return emptyList()
    val values = when {
        element.isJsonArray -> element.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.primitiveString()
                item.isJsonObject -> item.asJsonObject.firstString("name", "title", "russian")
                else -> null
            }
        }
        element.isJsonPrimitive -> element.primitiveString()
            ?.split(',', '/', ';')
            .orEmpty()
        else -> emptyList()
    }
    return values.mapNotNull { it.cleanText()?.takeIf(String::isNotBlank) }.distinct()
}

private fun JsonElement.primitiveString(): String? = runCatching {
    takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.let { primitive ->
        when {
            primitive.isString -> primitive.asString
            primitive.isNumber || primitive.isBoolean -> primitive.toString()
            else -> null
        }
    }
}.getOrNull()

private fun JsonElement.primitiveLong(): Long? = primitiveString()?.trim()?.let { raw ->
    raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

private fun JsonElement.primitiveDouble(): Double? = primitiveString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

private fun JsonElement.primitiveBoolean(): Boolean? = primitiveString()?.trim()?.lowercase()?.let { raw ->
    when (raw) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> null
    }
}

private fun String.cleanText(): String? = trim().takeIf(String::isNotBlank)

private fun String.extractQuality(): Int? = Regex("(?:^|\\D)(2160|1440|1080|720|576|540|480|360|240)(?:p|\\D|$)")
    .find(this)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray()
