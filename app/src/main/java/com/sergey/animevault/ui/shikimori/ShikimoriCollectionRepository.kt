package com.sergey.animevault.ui.shikimori

import com.sergey.animevault.data.titles.*

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ShikimoriCollectionRef(val id: Long, val isTopic: Boolean)
data class ImportedShikiAnime(
    val id: Long,
    val title: String,
    val posterUrl: String? = null,
    val year: Int? = null,
    val score: String? = null,
    val names: AnimeNames = AnimeNames.original(title),
)
data class ImportedShikiCollection(
    val collectionId: Long,
    val title: String,
    val sourceUrl: String,
    val animes: List<ImportedShikiAnime>,
)
data class ShikimoriCollectionPreview(
    val topicId: Long,
    val collectionId: Long,
    val title: String,
    val commentsCount: Int,
)

/** Never fetch arbitrary user-supplied URLs; only known hosts and numeric IDs. */
internal fun parseShikimoriCollectionUrl(input: String): ShikimoriCollectionRef? {
    val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host?.lowercase() !in setOf(
            "shikimori.one", "shikimori.io", "www.shikimori.one", "www.shikimori.io",
        )) return null
    if (uri.userInfo != null || uri.port != -1) return null
    val match = Regex("^/(collections|topics|api/topics)/([0-9]{1,10})(?:-[^/]+)?/?$")
        .matchEntire(uri.path.orEmpty()) ?: return null
    val id = match.groupValues[2].toLongOrNull()
        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
    return ShikimoriCollectionRef(id, match.groupValues[1] != "collections")
}

private fun cleanCollectionTitle(raw: String): String =
    raw.replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim().take(160)

/** Only anime links/BBCode within the topic body, not loose URLs in arbitrary text. */
internal fun parseShikimoriCollectionEntries(html: String): List<ImportedShikiAnime> {
    val found = linkedMapOf<Long, String>()
    val anchors = Regex("""(?is)<a\b[^>]*href\s*=\s*["'](?:https?://(?:www\.)?shikimori\.(?:one|io))?/animes/([0-9]{1,9})(?:-[^"']*)?["'][^>]*>(.*?)</a>""")
    anchors.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (id <= 0L || (found.size >= 250 && id !in found)) return@forEach
        val title = cleanCollectionTitle(match.groupValues[2])
        if (id !in found || found[id]?.startsWith("Аниме #") == true) {
            found[id] = title.ifBlank { found[id] ?: "Аниме #$id" }
        }
    }
    val bbcodes = Regex("""(?i)\[anime\s*[=:]\s*([0-9]{1,9})\s*]""")
    bbcodes.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (id > 0 && found.size < 250) found.putIfAbsent(id, "Аниме #$id")
    }
    val cards = Regex("""(?i)\bdata-href\s*=\s*["'](?:https?://(?:www\.)?shikimori\.(?:one|io))?/animes/([0-9]{1,9})(?:-[^"']*)?["']""")
    cards.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (id > 0 && found.size < 250) found.putIfAbsent(id, "Аниме #$id")
    }
    return found.map { (id, title) -> ImportedShikiAnime(id, title) }
}

internal fun parseShikimoriTopic(json: String, fallbackId: Long, url: String): ImportedShikiCollection {
    val topic = JSONObject(json)
    val linkedType = topic.optString("linked_type")
    if (linkedType.isNotBlank() && !linkedType.equals("Collection", ignoreCase = true)) {
        throw IllegalStateException("Это не коллекция Shikimori")
    }
    val entries = parseShikimoriCollectionEntries(topic.optString("html_body"))
        .ifEmpty { parseShikimoriCollectionEntries(topic.optString("body")) }
    if (entries.isEmpty()) throw IllegalStateException(
        "API вернул коллекцию без распознаваемых аниме. Возможно, список скрыт или формат изменился.",
    )
    return ImportedShikiCollection(
        collectionId = topic.optLong("linked_id").takeIf { it > 0 } ?: fallbackId,
        title = cleanCollectionTitle(
            topic.optString("topic_title").ifBlank { topic.optString("title") },
        ).ifBlank { "Коллекция #$fallbackId" },
        sourceUrl = url,
        animes = entries,
    )
}

internal fun parseShikimoriCollectionCatalog(json: String): List<ShikimoriCollectionPreview> {
    val array = JSONArray(json)
    val result = linkedMapOf<Long, ShikimoriCollectionPreview>()
    for (i in 0 until array.length()) {
        val topic = array.optJSONObject(i) ?: continue
        val type = topic.optString("linked_type")
        if (type.isNotBlank() && !type.equals("Collection", ignoreCase = true)) continue
        val topicId = topic.optLong("id")
        val collectionId = topic.optLong("linked_id")
        if (topicId <= 0 || collectionId <= 0) continue
        result.putIfAbsent(topicId, ShikimoriCollectionPreview(
            topicId, collectionId,
            cleanCollectionTitle(topic.optString("topic_title")
                .ifBlank { topic.optString("title") }).ifBlank { "Коллекция #$collectionId" },
            topic.optInt("comments_count").coerceAtLeast(0),
        ))
    }
    return result.values.toList()
}

/** Batch metadata resolver: GraphQL accepts up to 50 anime IDs per request. */
internal fun mergeShikimoriAnimeMetadata(
    entries: List<ImportedShikiAnime>, json: String,
): List<ImportedShikiAnime> {
    val root = JSONObject(json)
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) throw IllegalStateException("GraphQL вернул ошибку")
    val animes = root.optJSONObject("data")?.optJSONArray("animes")
        ?: throw IllegalStateException("Неожиданный ответ GraphQL")
    val byId = mutableMapOf<Long, ImportedShikiAnime>()
    for (i in 0 until animes.length()) {
        val item = animes.optJSONObject(i) ?: continue
        val id = item.optString("id").toLongOrNull() ?: continue
        val name = item.optString("russian").ifBlank { item.optString("name") }.trim()
        val poster = item.optJSONObject("poster")?.optString("mainUrl")
            ?.takeIf { it.startsWith("https://") }
        val year = item.optJSONObject("airedOn")?.optInt("year")?.takeIf { it in 1900..2200 }
        val score = item.optString("score").takeIf { it.isNotBlank() && it != "0" }
        if (name.isNotBlank()) byId[id] = ImportedShikiAnime(id, name, poster, year, score, item.animeNames())
    }
    return entries.map { old ->
        byId[old.id]?.let { fresh ->
            fresh.copy(
                posterUrl = fresh.posterUrl ?: old.posterUrl,
                year = fresh.year ?: old.year,
                score = fresh.score ?: old.score,
            )
        } ?: old
    }
}

class ShikimoriCollectionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build(),
) {
    /** Browse the public collection-topic feed, newest first. No HTML-page scraping. */
    suspend fun discover(page: Int = 1): List<ShikimoriCollectionPreview> = withContext(Dispatchers.IO) {
        require(page in 1..100) { "Недопустимая страница" }
        val endpoints = listOf(
            "https://shikimori.one/api/topics?type=collection&page=$page&limit=20",
            "https://shikimori.one/api/topics?forum=collections&page=$page&limit=20",
        )
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val list = parseShikimoriCollectionCatalog(get(endpoint))
                if (list.isNotEmpty() || page > 1) return@withContext list
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
            ?: IllegalStateException("Не удалось загрузить коллекции")
        emptyList()
    }

    suspend fun importFromUrl(input: String): ImportedShikiCollection = withContext(Dispatchers.IO) {
        val ref = parseShikimoriCollectionUrl(input) ?: throw IllegalArgumentException(
            "Вставьте ссылку https://shikimori.one/collections/123 или /topics/123",
        )
        val topicId = if (ref.isTopic) ref.id else findTopicId(ref.id)
        importTopicInternal(topicId, if (ref.isTopic) null else ref.id)
    }

    suspend fun importTopic(topicId: Long, collectionId: Long): ImportedShikiCollection =
        withContext(Dispatchers.IO) {
            require(topicId > 0 && collectionId > 0)
            importTopicInternal(topicId, collectionId)
        }

    suspend fun refreshSaved(saved: ImportedShikiCollection): ImportedShikiCollection =
        withContext(Dispatchers.IO) { saved.copy(animes = enrich(saved.animes)) }

    private suspend fun importTopicInternal(
        topicId: Long, expectedCollectionId: Long?,
    ): ImportedShikiCollection {
        val parsed = parseShikimoriTopic(
            get("https://shikimori.one/api/topics/$topicId"),
            expectedCollectionId ?: topicId,
            "https://shikimori.one/topics/$topicId",
        )
        if (expectedCollectionId != null && parsed.collectionId != expectedCollectionId) {
            throw IllegalStateException("Shikimori вернул другую коллекцию")
        }
        return parsed.copy(
            sourceUrl = "https://shikimori.one/collections/${parsed.collectionId}",
            animes = enrich(parsed.animes),
        )
    }

    private fun findTopicId(collectionId: Long): Long {
        // Do not stop at a 422 for one filter combination; older importer did.
        val urls = listOf(
            "https://shikimori.one/api/topics?forum=collections&linked_type=Collection&linked_id=$collectionId&limit=10",
            "https://shikimori.one/api/topics?linked_type=Collection&linked_id=$collectionId&limit=30",
            "https://shikimori.one/api/topics?type=collection&linked_id=$collectionId&limit=30",
        )
        for (url in urls) {
            val body = runCatching { get(url) }.getOrNull() ?: continue
            val topics = runCatching { JSONArray(body) }.getOrNull() ?: continue
            for (i in 0 until topics.length()) {
                val topic = topics.optJSONObject(i) ?: continue
                if (topic.optLong("linked_id") == collectionId &&
                    topic.optString("linked_type").equals("Collection", ignoreCase = true)) {
                    return topic.optLong("id").takeIf { it > 0 } ?: continue
                }
            }
        }
        throw IllegalStateException(
            "Shikimori не нашёл тему коллекции через API. " +
                "Попробуйте ссылку /topics/… с её страницы.",
        )
    }

    private suspend fun enrich(entries: List<ImportedShikiAnime>): List<ImportedShikiAnime> {
        var result = entries
        for ((index, batch) in entries.chunked(40).withIndex()) {
            if (index > 0) delay(600)
            val ids = batch.joinToString(",") { it.id.toString() }
            val query = """{ animes(ids: "$ids", limit: 50, censored: false) { id name russian english japanese synonyms score airedOn { year } poster { mainUrl } } }"""
            val body = JSONObject().put("query", query).toString()
            val payload = runCatching {
                execute(Request.Builder()
                    .url("https://shikimori.one/api/graphql")
                    .header("Accept", "application/json")
                    .header("User-Agent", "AnimeVault/3.0.2 (Shikimori collections)")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build())
            }.getOrNull() ?: continue // Preserve cached titles when the metadata API is unavailable.
            result = runCatching { mergeShikimoriAnimeMetadata(result, payload) }.getOrDefault(result)
        }
        // The public GraphQL service sometimes omits older or renamed entries.
        // Resolve only still-unknown titles through REST, not every anime in the list.
        for ((index, unresolved) in result.filter { it.title.startsWith("Аниме #") }.take(12).withIndex()) {
            if (index > 0) delay(650)
            val details = try {
                JSONObject(get("https://shikimori.one/api/animes/${unresolved.id}"))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                continue
            }
            val name = details.optString("russian").ifBlank { details.optString("name") }.trim()
            if (name.isBlank()) continue
            val image = details.optJSONObject("image")?.optString("original")
            val poster = image?.let {
                when {
                    it.startsWith("https://") -> it
                    it.startsWith("/") -> "https://shikimori.one$it"
                    else -> null
                }
            }
            val year = details.optString("aired_on").take(4).toIntOrNull()
                ?.takeIf { it in 1900..2200 }
            val score = details.optString("score").takeIf { it.isNotBlank() && it != "0" }
            result = result.map {
                if (it.id == unresolved.id) it.copy(
                    title = name, posterUrl = poster ?: it.posterUrl,
                    year = year ?: it.year, score = score ?: it.score,
                ) else it
            }
        }
        return result
    }

    private fun get(url: String): String = execute(Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", "AnimeVault/3.0.2 (Shikimori collections)")
        .build())

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        if (response.code == 429) throw IllegalStateException(
            "Shikimori ограничил частоту запросов. Повторите позже.",
        )
        if (!response.isSuccessful) throw IllegalStateException("Shikimori API: HTTP ${response.code}")
        response.body?.string()?.takeIf { it.length <= 2_000_000 }
            ?: throw IllegalStateException("Пустой или слишком большой ответ Shikimori")
    }
}

/** Keep the original v3 key: all saved collections survive the app update. */
class ShikimoriCollectionStore(context: Context, private val titleStore: AnimeTitleStore? = null) {
    private val contextApp = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(
        "animevault_shikimori_collections_v3", Context.MODE_PRIVATE,
    )

    fun read(): List<ImportedShikiCollection> = runCatching {
        val array = JSONArray(prefs.getString("collections", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val animes = item.optJSONArray("animes") ?: JSONArray()
                val entries = buildList {
                    for (j in 0 until animes.length()) {
                        val anime = animes.optJSONObject(j) ?: continue
                        val id = anime.optLong("id")
                        if (id > 0) add(ImportedShikiAnime(
                            id = id,
                            title = anime.optString("title").ifBlank { "Аниме #$id" },
                            posterUrl = anime.optString("poster").takeIf { it.startsWith("https://") },
                            year = anime.optInt("year").takeIf { it in 1900..2200 },
                            score = anime.optString("score").takeIf { it.isNotBlank() },
                            names = anime.optJSONObject("names")?.animeNames() ?: AnimeNames.original(anime.optString("title")),
                        ))
                    }
                }
                if (entries.isNotEmpty()) add(ImportedShikiCollection(
                    item.optLong("id"), item.optString("title"), item.optString("url"), entries,
                ))
            }
        }
    }.getOrDefault(emptyList()).also(::indexNames)

    private fun indexNames(collections: List<ImportedShikiCollection>) {
        val names = collections.take(30).flatMap { it.animes.take(250) }.associate { anime ->
            "shikimori:${anime.id}" to anime.names.overlay(AnimeNames(synonyms = listOf(anime.title)))
        }
        val target = titleStore ?: (contextApp as? com.sergey.animevault.AnimeVaultApplication)?.container?.animeTitles
        target?.putBatch(names)
    }

    fun save(collections: List<ImportedShikiCollection>) {
        val array = JSONArray()
        for (item in collections.take(30)) {
            val animes = JSONArray()
            item.animes.take(250).forEach { anime ->
                animes.put(JSONObject().put("id", anime.id).put("title", anime.title)
                    .put("names", JSONObject(com.google.gson.Gson().toJson(anime.names)))
                    .put("poster", anime.posterUrl ?: "")
                    .put("year", anime.year ?: 0).put("score", anime.score ?: ""))
            }
            array.put(JSONObject().put("id", item.collectionId).put("title", item.title)
                .put("url", item.sourceUrl).put("animes", animes))
        }
        prefs.edit { putString("collections", array.toString()) }
        indexNames(collections)
    }
}
