package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.sergey.animevault.data.cache.InFlightRequestCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

data class TenraiNamedResource(
    val malId: Long?,
    val name: String,
    val url: String?,
)

data class TenraiNamedLink(
    val name: String,
    val url: String,
)

data class TenraiTrailer(
    val youtubeId: String?,
    val url: String?,
    val embedUrl: String?,
    val thumbnailUrl: String?,
)

data class TenraiRelationItem(
    val relation: String,
    val malId: Long,
    val type: String?,
    val mediaType: String?,
    val name: String,
    val url: String?,
    val imageUrl: String?,
)

data class TenraiAnimeOverview(
    val malId: Long,
    val title: String,
    val englishTitle: String?,
    val japaneseTitle: String?,
    val posterUrl: String?,
    val largePosterUrl: String?,
    val type: String?,
    val source: String?,
    val episodeCount: Int?,
    val status: String?,
    val airing: Boolean,
    val duration: String?,
    val rating: String?,
    val score: Double?,
    val scoredBy: Int?,
    val rank: Int?,
    val popularity: Int?,
    val members: Int?,
    val favorites: Int?,
    val synopsis: String?,
    val background: String?,
    val season: String?,
    val year: Int?,
    val broadcast: String?,
    val studios: List<TenraiNamedResource>,
    val genres: List<String>,
    val relations: List<TenraiRelationItem>,
    val openings: List<String>,
    val endings: List<String>,
    val externalLinks: List<TenraiNamedLink>,
    val streamingLinks: List<TenraiNamedLink>,
    val trailer: TenraiTrailer?,
    val metadataSource: String = "Tenrai",
    val isStale: Boolean = false,
)

data class TenraiStaffMember(
    val malId: Long,
    val name: String,
    val imageUrl: String?,
    val url: String?,
    val positions: List<String>,
)

data class TenraiRecommendation(
    val malId: Long,
    val title: String,
    val imageUrl: String?,
    val url: String?,
    val votes: Int,
)

data class TenraiEpisodeMetadata(
    val number: Int,
    val title: String?,
    val japaneseTitle: String?,
    val romanizedTitle: String?,
    val airedAt: String?,
    val score: Double?,
    val filler: Boolean,
    val recap: Boolean,
    val url: String?,
)

data class TenraiAnimeStatistics(
    val watching: Int,
    val completed: Int,
    val onHold: Int,
    val dropped: Int,
    val planToWatch: Int,
    val total: Int,
)

data class TenraiPicture(
    val imageUrl: String?,
    val largeImageUrl: String?,
)

data class TenraiThemeList(
    val openings: List<String>,
    val endings: List<String>,
)

data class TenraiCatalogItem(
    val malId: Long,
    val title: String,
    val englishTitle: String?,
    val japaneseTitle: String?,
    val imageUrl: String?,
    val type: String?,
    val episodeCount: Int?,
    val status: String?,
    val score: Double?,
    val year: Int?,
    val season: String?,
    val broadcast: String?,
)

data class TenraiPage<T>(
    val items: List<T>,
    val page: Int,
    val lastPage: Int,
    val hasNextPage: Boolean,
)

data class TenraiMetadataBundle(
    val overview: TenraiAnimeOverview,
    val staff: List<TenraiStaffMember>,
    val recommendations: List<TenraiRecommendation>,
    val statistics: TenraiAnimeStatistics?,
)

/**
 * Rich metadata facade over Tenrai's Jikan-compatible anime endpoints.
 *
 * Heavy lists are deliberately lazy: opening a title loads the compact bundle only;
 * episodes, pictures, themes and season/schedule feeds are fetched only when requested.
 */
class TenraiMetadataRepository(
    private val client: TenraiClient = TenraiClient(),
    private val gson: Gson = Gson(),
    private val fallback: TenraiMetadataFallback? = null,
) {
    private val overviewCache = InFlightRequestCache<Long, TenraiAnimeOverview>(
        maxEntries = 96,
        ttlMs = DETAILS_TTL_MS,
    )
    private val staffCache = InFlightRequestCache<Long, List<TenraiStaffMember>>(
        maxEntries = 64,
        ttlMs = DETAILS_TTL_MS,
    )
    private val recommendationCache = InFlightRequestCache<Long, List<TenraiRecommendation>>(
        maxEntries = 64,
        ttlMs = DETAILS_TTL_MS,
    )
    private val statisticsCache = InFlightRequestCache<Long, TenraiAnimeStatistics?>(
        maxEntries = 64,
        ttlMs = STATS_TTL_MS,
    )
    private val episodesCache = InFlightRequestCache<Long, List<TenraiEpisodeMetadata>>(
        maxEntries = 48,
        ttlMs = EPISODES_TTL_MS,
    )
    private val episodePageCache = InFlightRequestCache<String, TenraiPage<TenraiEpisodeMetadata>>(
        maxEntries = 96,
        ttlMs = EPISODES_TTL_MS,
    )
    private val picturesCache = InFlightRequestCache<Long, List<TenraiPicture>>(
        maxEntries = 48,
        ttlMs = DETAILS_TTL_MS,
    )
    private val themesCache = InFlightRequestCache<Long, TenraiThemeList>(
        maxEntries = 64,
        ttlMs = DETAILS_TTL_MS,
    )
    private val catalogCache = InFlightRequestCache<String, TenraiPage<TenraiCatalogItem>>(
        maxEntries = 48,
        ttlMs = CATALOG_TTL_MS,
    )

    fun healthSnapshot(): TenraiHealthSnapshot = client.healthSnapshot()

    suspend fun loadBundle(malId: Long): TenraiMetadataBundle {
        requireValidMalId(malId)
        return supervisorScope {
            val overview = async { getOverview(malId) }
            val staff = async { optional<List<TenraiStaffMember>>(emptyList()) { getStaff(malId) } }
            val recommendations = async { optional<List<TenraiRecommendation>>(emptyList()) { getRecommendations(malId) } }
            val statistics = async { optional<TenraiAnimeStatistics?>(null) { getStatistics(malId) } }
            TenraiMetadataBundle(
                overview = overview.await(),
                staff = staff.await(),
                recommendations = recommendations.await(),
                statistics = statistics.await(),
            )
        }
    }

    suspend fun getOverview(malId: Long): TenraiAnimeOverview {
        requireValidMalId(malId)
        return overviewCache.getOrLoad(malId) {
            try {
                val response = client.getResult("anime/$malId/full")
                parseTenraiFull(
                    gson = gson,
                    json = response.body,
                    metadataSource = "Tenrai",
                    isStale = response.origin == TenraiResponseOrigin.STALE_CACHE,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fallback?.findOverviewByMalId(malId) ?: throw error
            }
        }
    }

    suspend fun getStaff(malId: Long): List<TenraiStaffMember> {
        requireValidMalId(malId)
        return staffCache.getOrLoad(malId) {
            parseTenraiStaff(gson, client.get("anime/$malId/staff"))
        }
    }

    suspend fun getRecommendations(malId: Long): List<TenraiRecommendation> {
        requireValidMalId(malId)
        return recommendationCache.getOrLoad(malId) {
            parseTenraiRecommendations(gson, client.get("anime/$malId/recommendations"))
        }
    }

    suspend fun getStatistics(malId: Long): TenraiAnimeStatistics? {
        requireValidMalId(malId)
        return statisticsCache.getOrLoad(malId) {
            parseTenraiStatistics(gson, client.get("anime/$malId/statistics"))
        }
    }

    suspend fun getEpisodesPage(
        malId: Long,
        page: Int = 1,
    ): TenraiPage<TenraiEpisodeMetadata> {
        requireValidMalId(malId)
        require(page > 0) { "Некорректная страница" }
        return episodePageCache.getOrLoad("$malId|$page") {
            parseTenraiEpisodesPage(
                gson,
                client.get(
                    path = "anime/$malId/episodes",
                    queryParameters = mapOf("page" to page.toString()),
                ),
            )
        }
    }

    suspend fun getAllEpisodes(malId: Long): List<TenraiEpisodeMetadata> {
        requireValidMalId(malId)
        return episodesCache.getOrLoad(malId) {
            buildList {
                var page = 1
                while (page <= MAX_EPISODE_PAGES) {
                    val result = getEpisodesPage(malId, page)
                    addAll(result.items)
                    if (!result.hasNextPage || result.lastPage <= page) break
                    page += 1
                }
            }.distinctBy(TenraiEpisodeMetadata::number)
                .sortedBy(TenraiEpisodeMetadata::number)
        }
    }

    suspend fun getEpisode(malId: Long, episodeNumber: Int): TenraiEpisodeMetadata? {
        requireValidMalId(malId)
        require(episodeNumber > 0) { "Некорректный номер серии" }
        return parseTenraiEpisode(
            gson,
            client.get("anime/$malId/episodes/$episodeNumber"),
        )
    }

    suspend fun getPictures(malId: Long): List<TenraiPicture> {
        requireValidMalId(malId)
        return picturesCache.getOrLoad(malId) {
            parseTenraiPictures(gson, client.get("anime/$malId/pictures"))
        }
    }

    suspend fun getThemes(malId: Long): TenraiThemeList {
        requireValidMalId(malId)
        return themesCache.getOrLoad(malId) {
            parseTenraiThemes(gson, client.get("anime/$malId/themes"))
        }
    }

    suspend fun searchAnime(
        query: String,
        page: Int = 1,
        sfw: Boolean = true,
    ): TenraiPage<TenraiCatalogItem> {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        require(clean.length >= 2) { "Слишком короткий поисковый запрос" }
        require(page > 0) { "Некорректная страница" }
        val key = "search|${clean.lowercase()}|$page|$sfw"
        return catalogCache.getOrLoad(key) {
            try {
                parseTenraiCatalogPage(
                    gson,
                    client.get(
                        path = "anime",
                        queryParameters = buildMap<String, String?> {
                            put("q", clean)
                            put("page", page.toString())
                            if (sfw) put("sfw", "true")
                        },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fallback?.searchAnime(clean, page, sfw) ?: throw error
            }
        }
    }

    suspend fun getSchedule(
        day: String? = null,
        page: Int = 1,
        sfw: Boolean = true,
    ): TenraiPage<TenraiCatalogItem> {
        require(page > 0) { "Некорректная страница" }
        val normalizedDay = day?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val key = "schedule|${normalizedDay.orEmpty()}|$page|$sfw"
        return catalogCache.getOrLoad(key) {
            parseTenraiCatalogPage(
                gson,
                client.get(
                    path = "schedules",
                    queryParameters = buildMap<String, String?> {
                        normalizedDay?.let { put("filter", it) }
                        put("page", page.toString())
                        if (sfw) put("sfw", "true")
                    },
                ),
            )
        }
    }

    suspend fun getCurrentSeason(page: Int = 1, sfw: Boolean = true): TenraiPage<TenraiCatalogItem> =
        getSeasonPage("seasons/now", page, sfw)

    suspend fun getUpcomingSeason(page: Int = 1, sfw: Boolean = true): TenraiPage<TenraiCatalogItem> =
        getSeasonPage("seasons/upcoming", page, sfw)

    private suspend fun getSeasonPage(
        path: String,
        page: Int,
        sfw: Boolean,
    ): TenraiPage<TenraiCatalogItem> {
        require(page > 0) { "Некорректная страница" }
        val key = "$path|$page|$sfw"
        return catalogCache.getOrLoad(key) {
            parseTenraiCatalogPage(
                gson,
                client.get(
                    path = path,
                    queryParameters = buildMap<String, String?> {
                        put("page", page.toString())
                        if (sfw) put("sfw", "true")
                    },
                ),
            )
        }
    }

    private companion object {
        const val DETAILS_TTL_MS = 6 * 60 * 60_000L
        const val STATS_TTL_MS = 60 * 60_000L
        const val EPISODES_TTL_MS = 2 * 60 * 60_000L
        const val CATALOG_TTL_MS = 15 * 60_000L
        const val MAX_EPISODE_PAGES = 100
    }
}


private suspend fun <T> optional(defaultValue: T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    defaultValue
}

internal fun parseTenraiFull(
    gson: Gson,
    json: String,
    metadataSource: String = "Tenrai",
    isStale: Boolean = false,
): TenraiAnimeOverview {
    val data = parseDataObject(gson, json)
    val malId = data.long("mal_id") ?: throw TenraiProtocolException("Tenrai /full не содержит mal_id")
    val title = data.string("title")
        ?: data.arrayObjects("titles").firstNotNullOfOrNull { it.string("title") }
        ?: throw TenraiProtocolException("Tenrai /full не содержит title")

    val images = data.obj("images")
    val jpg = images?.obj("jpg")
    val webp = images?.obj("webp")
    val trailer = data.obj("trailer")?.let { trailerObject ->
        val trailerImages = trailerObject.obj("images")
        TenraiTrailer(
            youtubeId = trailerObject.string("youtube_id"),
            url = trailerObject.string("url"),
            embedUrl = trailerObject.string("embed_url"),
            thumbnailUrl = trailerImages?.string("maximum_image_url")
                ?: trailerImages?.string("large_image_url")
                ?: trailerImages?.string("medium_image_url")
                ?: trailerImages?.string("image_url"),
        ).takeIf { it.youtubeId != null || it.url != null || it.embedUrl != null }
    }

    val relations = data.arrayObjects("relations").flatMap { group ->
        val relation = group.string("relation") ?: "Связано"
        group.arrayObjects("entry").mapNotNull { entry ->
            val id = entry.long("mal_id") ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            TenraiRelationItem(
                relation = relation,
                malId = id,
                type = entry.string("type"),
                mediaType = entry.string("media_type"),
                name = name,
                url = entry.string("url"),
                imageUrl = imageUrl(entry.obj("images")),
            )
        }
    }.distinctBy { "${it.relation}:${it.malId}:${it.type.orEmpty()}" }

    val theme = data.obj("theme")
    return TenraiAnimeOverview(
        malId = malId,
        title = title,
        englishTitle = data.string("title_english"),
        japaneseTitle = data.string("title_japanese"),
        posterUrl = webp?.string("image_url") ?: jpg?.string("image_url"),
        largePosterUrl = webp?.string("large_image_url") ?: jpg?.string("large_image_url"),
        type = data.string("type"),
        source = data.string("source"),
        episodeCount = data.int("episodes"),
        status = data.string("status"),
        airing = data.boolean("airing") ?: false,
        duration = data.string("duration"),
        rating = data.string("rating"),
        score = data.double("score"),
        scoredBy = data.int("scored_by"),
        rank = data.int("rank"),
        popularity = data.int("popularity"),
        members = data.int("members"),
        favorites = data.int("favorites"),
        synopsis = data.string("synopsis"),
        background = data.string("background"),
        season = data.string("season"),
        year = data.int("year"),
        broadcast = data.obj("broadcast")?.string("string"),
        studios = data.arrayObjects("studios").mapNotNull(::namedResource),
        genres = (
            data.arrayObjects("genres") +
                data.arrayObjects("explicit_genres") +
                data.arrayObjects("themes") +
                data.arrayObjects("demographics")
            ).mapNotNull { it.string("name") }.distinct(),
        relations = relations,
        openings = theme?.stringList("openings").orEmpty(),
        endings = theme?.stringList("endings").orEmpty(),
        externalLinks = data.arrayObjects("external").mapNotNull(::namedLink),
        streamingLinks = data.arrayObjects("streaming").mapNotNull(::namedLink),
        trailer = trailer,
        metadataSource = metadataSource,
        isStale = isStale,
    )
}

internal fun parseTenraiStaff(gson: Gson, json: String): List<TenraiStaffMember> =
    parseDataArray(gson, json).mapNotNull { entry ->
        val person = entry.obj("person") ?: return@mapNotNull null
        val malId = person.long("mal_id") ?: return@mapNotNull null
        val name = person.string("name") ?: return@mapNotNull null
        TenraiStaffMember(
            malId = malId,
            name = name,
            imageUrl = imageUrl(person.obj("images")),
            url = person.string("url"),
            positions = entry.stringList("positions"),
        )
    }.distinctBy(TenraiStaffMember::malId)

internal fun parseTenraiRecommendations(gson: Gson, json: String): List<TenraiRecommendation> =
    parseDataArray(gson, json).mapNotNull { item ->
        val entry = item.obj("entry") ?: return@mapNotNull null
        val malId = entry.long("mal_id") ?: return@mapNotNull null
        val title = entry.string("title") ?: entry.string("name") ?: return@mapNotNull null
        TenraiRecommendation(
            malId = malId,
            title = title,
            imageUrl = imageUrl(entry.obj("images")),
            url = entry.string("url") ?: item.string("url"),
            votes = item.int("votes") ?: 0,
        )
    }.distinctBy(TenraiRecommendation::malId)
        .sortedByDescending(TenraiRecommendation::votes)

internal fun parseTenraiEpisodesPage(gson: Gson, json: String): TenraiPage<TenraiEpisodeMetadata> {
    val root = parseRoot(gson, json)
    val items = root.arrayObjects("data").mapNotNull(::episodeFromJson)
    val pagination = root.obj("pagination")
    val current = pagination?.int("current_page") ?: 1
    val last = pagination?.int("last_visible_page") ?: current
    val hasNext = pagination?.boolean("has_next_page") ?: (current < last)
    return TenraiPage(items = items, page = current, lastPage = last, hasNextPage = hasNext)
}

internal fun parseTenraiEpisode(gson: Gson, json: String): TenraiEpisodeMetadata? {
    val data = parseRoot(gson, json).obj("data") ?: return null
    return episodeFromJson(data)
}

internal fun parseTenraiStatistics(gson: Gson, json: String): TenraiAnimeStatistics? {
    val data = parseRoot(gson, json).obj("data") ?: return null
    return TenraiAnimeStatistics(
        watching = data.int("watching") ?: 0,
        completed = data.int("completed") ?: 0,
        onHold = data.int("on_hold") ?: 0,
        dropped = data.int("dropped") ?: 0,
        planToWatch = data.int("plan_to_watch") ?: 0,
        total = data.int("total") ?: 0,
    )
}

internal fun parseTenraiPictures(gson: Gson, json: String): List<TenraiPicture> =
    parseDataArray(gson, json).map { item ->
        val webp = item.obj("webp")
        val jpg = item.obj("jpg")
        TenraiPicture(
            imageUrl = webp?.string("image_url") ?: jpg?.string("image_url"),
            largeImageUrl = webp?.string("large_image_url") ?: jpg?.string("large_image_url"),
        )
    }.filter { it.imageUrl != null || it.largeImageUrl != null }
        .distinctBy { it.largeImageUrl ?: it.imageUrl }

internal fun parseTenraiThemes(gson: Gson, json: String): TenraiThemeList {
    val data = parseDataObject(gson, json)
    return TenraiThemeList(
        openings = data.stringList("openings"),
        endings = data.stringList("endings"),
    )
}

internal fun parseTenraiCatalogPage(gson: Gson, json: String): TenraiPage<TenraiCatalogItem> {
    val root = parseRoot(gson, json)
    val items = root.arrayObjects("data").mapNotNull(::catalogItemFromJson)
    val pagination = root.obj("pagination")
    val current = pagination?.int("current_page") ?: 1
    val last = pagination?.int("last_visible_page") ?: current
    val hasNext = pagination?.boolean("has_next_page") ?: (current < last)
    return TenraiPage(items = items, page = current, lastPage = last, hasNextPage = hasNext)
}

private fun episodeFromJson(item: JsonObject): TenraiEpisodeMetadata? {
    val number = item.int("mal_id") ?: return null
    return TenraiEpisodeMetadata(
        number = number,
        title = item.string("title"),
        japaneseTitle = item.string("title_japanese"),
        romanizedTitle = item.string("title_romanji"),
        airedAt = item.string("aired"),
        score = item.double("score"),
        filler = item.boolean("filler") ?: false,
        recap = item.boolean("recap") ?: false,
        url = item.string("url"),
    )
}

private fun catalogItemFromJson(item: JsonObject): TenraiCatalogItem? {
    val malId = item.long("mal_id") ?: return null
    val title = item.string("title") ?: return null
    return TenraiCatalogItem(
        malId = malId,
        title = title,
        englishTitle = item.string("title_english"),
        japaneseTitle = item.string("title_japanese"),
        imageUrl = imageUrl(item.obj("images")),
        type = item.string("type"),
        episodeCount = item.int("episodes"),
        status = item.string("status"),
        score = item.double("score"),
        year = item.int("year"),
        season = item.string("season"),
        broadcast = item.obj("broadcast")?.string("string"),
    )
}

private fun namedResource(item: JsonObject): TenraiNamedResource? {
    val name = item.string("name") ?: return null
    return TenraiNamedResource(
        malId = item.long("mal_id"),
        name = name,
        url = item.string("url"),
    )
}

private fun namedLink(item: JsonObject): TenraiNamedLink? {
    val name = item.string("name") ?: return null
    val url = item.string("url") ?: return null
    return TenraiNamedLink(name = name, url = url)
}

private fun imageUrl(images: JsonObject?): String? {
    val webp = images?.obj("webp")
    val jpg = images?.obj("jpg")
    return webp?.string("large_image_url")
        ?: webp?.string("image_url")
        ?: jpg?.string("large_image_url")
        ?: jpg?.string("image_url")
}

private fun parseDataObject(gson: Gson, json: String): JsonObject =
    parseRoot(gson, json).obj("data")
        ?: throw TenraiProtocolException("Tenrai ответ не содержит объект data")

private fun parseDataArray(gson: Gson, json: String): List<JsonObject> =
    parseRoot(gson, json).arrayObjects("data")

private fun parseRoot(gson: Gson, json: String): JsonObject = try {
    gson.fromJson(json, JsonObject::class.java)
        ?: throw TenraiProtocolException("Tenrai вернул пустой JSON")
} catch (error: JsonParseException) {
    throw TenraiProtocolException("Tenrai вернул некорректный JSON", error)
} catch (error: IllegalStateException) {
    throw TenraiProtocolException("Tenrai вернул JSON неожиданной структуры", error)
}

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.array(name: String): JsonArray? =
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.arrayObjects(name: String): List<JsonObject> =
    array(name).orEmpty().mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject
    }

private fun JsonObject.stringList(name: String): List<String> =
    array(name).orEmpty().mapNotNull { element ->
        runCatching { element.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
    }.distinct()

private fun JsonObject.string(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.let { element ->
        runCatching { element.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
    }

private fun JsonObject.long(name: String): Long? =
    get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

private fun JsonObject.double(name: String): Double? =
    get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()

private fun requireValidMalId(malId: Long) {
    require(malId > 0L) { "Некорректный MAL ID" }
}
