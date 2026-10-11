package com.sergey.animevault.ui.shikimori

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ShikimoriCollectionRef(val id: Long, val isTopic: Boolean)
data class ImportedShikiAnime(val id: Long, val title: String)
data class ImportedShikiCollection(
    val collectionId: Long,
    val title: String,
    val sourceUrl: String,
    val animes: List<ImportedShikiAnime>,
)

/** Only pre-approved hosts and numeric IDs are passed to the Shikimori API (no URL fetching/SSRF). */
internal fun parseShikimoriCollectionUrl(input: String): ShikimoriCollectionRef? {
    val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("shikimori.one", "shikimori.io", "www.shikimori.one", "www.shikimori.io")) return null
    if (uri.userInfo != null || uri.port != -1) return null
    val match = Regex("^/(collections|topics|api/topics)/([0-9]{1,10})(?:-[^/]+)?/?$")
        .matchEntire(uri.path.orEmpty()) ?: return null
    val id = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
    return ShikimoriCollectionRef(id, match.groupValues[1] != "collections")
}

/** Parse only references to anime, not manga/characters and not random numbers inside text. */
internal fun parseShikimoriCollectionEntries(html: String): List<ImportedShikiAnime> {
    val found = linkedMapOf<Long, String>()
    val links = Regex("""(?is)<a\b[^>]*href\s*=\s*["'][^"']*/animes/([0-9]{1,9})(?:-[^"']*)?["'][^>]*>(.*?)</a>""")
    links.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        val title = cleanCollectionTitle(match.groupValues[2])
        if (id > 0L && (found.size < 250 || id in found)) {
            if (id !in found || found[id]?.startsWith("Аниме #") == true) {
                found[id] = title.ifBlank { found[id] ?: "Аниме #$id" }
            }
        }
    }
    val animeUrl = Regex("""(?i)(?:https?://(?:www\.)?shikimori\.(?:one|io))?/animes/([0-9]{1,9})(?=[^0-9]|$)""")
    animeUrl.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (id > 0L && found.size < 250) found.putIfAbsent(id, "Аниме #$id")
    }
    val bbcodes = Regex("""(?i)\[anime\s*[=:]\s*([0-9]{1,9})\s*]""")
    bbcodes.findAll(html).forEach { match ->
        val id = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (id > 0L && found.size < 250) found.putIfAbsent(id, "Аниме #$id")
    }
    return found.map { (id, title) -> ImportedShikiAnime(id, title) }
}

private fun cleanCollectionTitle(raw: String): String {
    val withoutTags = raw.replace(Regex("<[^>]*>"), " ")
    return withoutTags.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim().take(130)
}

internal fun parseShikimoriTopic(json: String, fallbackId: Long, url: String): ImportedShikiCollection {
    val topic = JSONObject(json)
    val linkedType = topic.optString("linked_type")
    if (linkedType.isNotBlank() && !linkedType.equals("Collection", ignoreCase = true)) {
        throw IllegalStateException("Тема Shikimori не относится к коллекциям")
    }
    val html = topic.optString("html_body")
    val entries = parseShikimoriCollectionEntries(html)
    if (entries.isEmpty()) throw IllegalStateException("Коллекция не содержит распознаваемых аниме (API изменился или список скрыт)")
    return ImportedShikiCollection(
        collectionId = topic.optLong("linked_id").takeIf { it > 0 } ?: fallbackId,
        title = cleanCollectionTitle(topic.optString("topic_title").ifBlank { topic.optString("title") })
            .ifBlank { "Коллекция #$fallbackId" },
        sourceUrl = url,
        animes = entries,
    )
}

class ShikimoriCollectionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build(),
) {
    suspend fun importFromUrl(input: String): ImportedShikiCollection = withContext(Dispatchers.IO) {
        val reference = parseShikimoriCollectionUrl(input)
            ?: throw IllegalArgumentException("Вставьте ссылку Shikimori вида https://shikimori.one/collections/123")
        val topicId = if (reference.isTopic) reference.id else findTopicId(reference.id)
        val source = if (reference.isTopic) "https://shikimori.one/topics/${reference.id}"
            else "https://shikimori.one/collections/${reference.id}"
        val collection = parseShikimoriTopic(get("https://shikimori.one/api/topics/$topicId"), reference.id, source)
        if (!reference.isTopic && collection.collectionId != reference.id) {
            throw IllegalStateException("API Shikimori вернул другую коллекцию")
        }
        collection
    }

    private fun findTopicId(collectionId: Long): Long {
        val path = "https://shikimori.one/api/topics?forum=collections&linked_type=Collection&linked_id=$collectionId&limit=10"
        val urls = listOf(
            path,
            "https://shikimori.one/api/topics?type=collection&linked_type=Collection&linked_id=$collectionId&limit=10",
        )
        for (url in urls) {
            val topics = JSONArray(get(url))
            for (index in 0 until topics.length()) {
                val topic = topics.optJSONObject(index) ?: continue
                if (topic.optLong("linked_id") == collectionId &&
                    topic.optString("linked_type").equals("Collection", ignoreCase = true)) {
                    return topic.optLong("id").takeIf { it > 0 }
                        ?: throw IllegalStateException("У коллекции нет ID темы")
                }
            }
        }
        throw IllegalStateException("Shikimori не вернул тему коллекции. Можно вставить прямую ссылку на её тему.")
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AnimeVault/3.0 (Android; Shikimori collections)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 429) throw IllegalStateException("Shikimori ограничил частоту запросов. Повторите позже")
            if (!response.isSuccessful) throw IllegalStateException("Shikimori API: HTTP ${response.code}")
            response.body?.string()?.takeIf { it.length <= 2_000_000 }
                ?: throw IllegalStateException("Пустой или слишком большой ответ Shikimori")
        }
    }
}

/** Saved locally, without replacing the user's existing anime library or watch statuses. */
class ShikimoriCollectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("animevault_shikimori_collections_v3", Context.MODE_PRIVATE)

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
                        if (id > 0) add(ImportedShikiAnime(id, anime.optString("title")))
                    }
                }
                if (entries.isNotEmpty()) add(ImportedShikiCollection(
                    item.optLong("id"), item.optString("title"), item.optString("url"), entries,
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(collections: List<ImportedShikiCollection>) {
        val array = JSONArray()
        for (item in collections.take(30)) {
            val animes = JSONArray()
            item.animes.take(250).forEach { anime ->
                animes.put(JSONObject().put("id", anime.id).put("title", anime.title))
            }
            array.put(JSONObject().put("id", item.collectionId).put("title", item.title)
                .put("url", item.sourceUrl).put("animes", animes))
        }
        prefs.edit { putString("collections", array.toString()) }
    }
}
