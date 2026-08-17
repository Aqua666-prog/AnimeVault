package com.sergey.animevault.data.animeon

import com.google.gson.Gson
import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProvider
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import com.sergey.animevault.util.runCatchingCancellable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeON (animeon.club) public API adapter.
 *
 * AnimeON is primarily a Ukrainian-language source. The API exposes translation
 * options and public episode file URLs. AnimeVault does not attempt to evade
 * regional restrictions or Premium/account gates: if the public endpoint denies
 * access, the provider reports that failure as-is.
 */
class AnimeOnProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val gson: Gson = Gson(),
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIME_ON,
        name = "AnimeON",
        description = "Украинский каталог AnimeON: несколько команд озвучки и публичные потоки",
        isExperimental = true,
        healthProbeQuery = "Naruto",
        searchHint = "Поиск AnimeON (украинское или английское название)",
    )

    private val metadataCache = ConcurrentHashMap<Int, AnimeOnSearchItemDto>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val query = search.trim()
        if (query.isBlank() || safePage > 1) {
            // The stable public API used by the upstream adapter is a search API.
            // Browse-mode can be added separately once its pagination contract is
            // pinned down; ordinary search and playback already work without it.
            return OnlineCatalogPage(emptyList(), safePage, safePage)
        }

        val url = "$BASE_URL/api/anime/search".toHttpUrl().newBuilder()
            .addQueryParameter("text", query)
            .build()
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .get()
                .onlineHeaders(referer = BASE_URL + "/")
                .build(),
            descriptor.name,
        )
        val response = runCatching { gson.fromJson(body, AnimeOnSearchResponseDto::class.java) }
            .getOrElse { throw OnlineSourceException("AnimeON вернул некорректный ответ поиска", it) }
        val cards = response.result.orEmpty()
            .asSequence()
            .filter { it.id > 0 }
            .onEach { metadataCache[it.id] = it }
            .take(limit.coerceAtLeast(1))
            .map(AnimeOnSearchItemDto::toCard)
            .toList()

        return OnlineCatalogPage(cards, 1, 1)
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val animeId = id.toIntOrNull()?.takeIf { it > 0 }
            ?: throw OnlineSourceException("AnimeON: некорректный ID тайтла")
        val translations = loadTranslations(animeId)
        if (translations.isEmpty()) {
            throw OnlineSourceException("AnimeON не нашёл доступных публичных переводов")
        }

        val semaphore = Semaphore(MAX_PARALLEL_TRANSLATIONS)
        val episodeSets = supervisorScope {
            translations.map { translation ->
                async {
                    semaphore.withPermit {
                        translation to runCatchingCancellable {
                            loadEpisodes(animeId, translation)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val episodes = mergeAnimeOnEpisodeSets(animeId, episodeSets)
        if (episodes.isEmpty()) {
            throw OnlineSourceException("AnimeON не отдал серии для этого тайтла")
        }

        val meta = metadataCache[animeId]
        val title = meta?.titleUa?.takeIf(String::isNotBlank)
            ?: meta?.titleEn?.takeIf(String::isNotBlank)
            ?: "AnimeON #$animeId"
        val english = meta?.titleEn?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        val availableTranslations = episodeSets.count { (_, list) -> list.isNotEmpty() }

        return OnlineReleaseDetails(
            providerId = OnlineProviderIds.ANIME_ON,
            providerName = "AnimeON",
            id = animeId.toString(),
            alias = animeId.toString(),
            name = title,
            englishName = english,
            posterUrl = meta?.image?.preview.toAnimeOnImageUrl(),
            year = meta?.releaseDate?.let(::extractYear),
            type = null,
            season = meta?.season?.takeIf { it > 0 }?.let { "Сезон $it" },
            episodeCount = episodes.size,
            description = null,
            notification = "Переводов AnimeON: $availableTranslations",
            genres = emptyList(),
            isOngoing = false,
            isBlocked = false,
            episodes = episodes,
        )
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> = supervisorScope {
        val references = decodeAnimeOnEpisodeRefs(episode.sourceRef)
        if (references.isEmpty()) return@supervisorScope episode.streams

        val semaphore = Semaphore(MAX_PARALLEL_STREAM_REQUESTS)
        val resolved = references.map { reference ->
            async {
                semaphore.withPermit {
                    runCatchingCancellable { loadEpisodeStream(reference) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()

        (episode.streams + resolved)
            .distinctBy { it.url to it.translation }
            .ifEmpty {
                throw OnlineSourceException(
                    "AnimeON не отдал ссылку на видео для ${episode.name ?: "выбранной серии"}",
                )
            }
    }

    private suspend fun loadTranslations(animeId: Int): List<AnimeOnTranslationOption> {
        val url = "$BASE_URL/api/player/$animeId/translations"
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .get()
                .onlineHeaders(referer = BASE_URL + "/")
                .build(),
            descriptor.name,
        )
        val response = runCatching { gson.fromJson(body, AnimeOnTranslationsResponseDto::class.java) }
            .getOrElse { throw OnlineSourceException("AnimeON изменил формат списка переводов", it) }
        return response.translations.orEmpty().flatMap { item ->
            val translationId = item.translation?.id?.takeIf { it > 0 } ?: return@flatMap emptyList()
            val translationName = item.translation.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Без названия"
            item.player.orEmpty().mapNotNull { player ->
                val playerId = player.id.takeIf { it > 0 } ?: return@mapNotNull null
                AnimeOnTranslationOption(
                    translationId = translationId,
                    playerId = playerId,
                    name = translationName,
                    playerName = player.name?.trim().takeUnless { it.isNullOrBlank() } ?: "Player",
                )
            }
        }.distinctBy { it.translationId to it.playerId }
    }

    private suspend fun loadEpisodes(
        animeId: Int,
        translation: AnimeOnTranslationOption,
    ): List<AnimeOnEpisodeDto> {
        val url = "$BASE_URL/api/player/$animeId/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("take", MAX_EPISODES.toString())
            .addQueryParameter("skip", "-1")
            .addQueryParameter("playerId", translation.playerId.toString())
            .addQueryParameter("translationId", translation.translationId.toString())
            .build()
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .get()
                .onlineHeaders(referer = BASE_URL + "/")
                .build(),
            descriptor.name,
        )
        return runCatching { gson.fromJson(body, AnimeOnEpisodesResponseDto::class.java) }
            .getOrElse { throw OnlineSourceException("AnimeON изменил формат списка серий", it) }
            .episodes
            .orEmpty()
            .filter { it.id > 0 || !it.fileUrl.isNullOrBlank() }
    }

    private suspend fun loadEpisodeStream(reference: AnimeOnEpisodeRef): OnlineStream {
        val url = "$BASE_URL/api/player/${reference.episodeId}/episode"
        val body = client.executeText(
            Request.Builder()
                .url(url)
                .get()
                .onlineHeaders(referer = BASE_URL + "/")
                .build(),
            descriptor.name,
        )
        val response = runCatching { gson.fromJson(body, AnimeOnEpisodeStreamDto::class.java) }
            .getOrElse { throw OnlineSourceException("AnimeON изменил формат ссылки на видео", it) }
        val videoUrl = response.videoUrl?.toAbsoluteAnimeOnUrl()
            ?: throw OnlineSourceException("AnimeON вернул пустую ссылку на видео")
        return OnlineStream(
            id = "animeon:${reference.episodeId}:${reference.translationId}:${reference.playerId}",
            quality = null,
            url = videoUrl,
            type = when {
                videoUrl.contains(".m3u8", ignoreCase = true) -> OnlineStreamType.HLS
                videoUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true) -> OnlineStreamType.MP4
                else -> OnlineStreamType.EMBED
            },
            headers = mapOf(
                "Referer" to "$BASE_URL/",
                "Origin" to BASE_URL,
            ),
            translation = reference.translationName,
            sourceName = "AnimeON · ${reference.playerName}",
        )
    }

    private companion object {
        const val BASE_URL = "https://animeon.club"
        const val MAX_EPISODES = 2_000
        const val MAX_PARALLEL_TRANSLATIONS = 4
        const val MAX_PARALLEL_STREAM_REQUESTS = 4
    }
}

internal data class AnimeOnSearchResponseDto(
    val result: List<AnimeOnSearchItemDto>? = emptyList(),
)

internal data class AnimeOnSearchItemDto(
    val id: Int = 0,
    val season: Int? = null,
    val imdbId: String? = null,
    val titleUa: String? = null,
    val titleEn: String? = null,
    val releaseDate: String? = null,
    val image: AnimeOnImageDto? = null,
) {
    fun toCard(): OnlineReleaseCard {
        val title = titleUa?.takeIf(String::isNotBlank)
            ?: titleEn?.takeIf(String::isNotBlank)
            ?: "AnimeON #$id"
        return OnlineReleaseCard(
            providerId = OnlineProviderIds.ANIME_ON,
            providerName = "AnimeON",
            id = id.toString(),
            alias = id.toString(),
            name = title,
            englishName = titleEn?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
            posterUrl = image?.preview.toAnimeOnImageUrl(),
            year = releaseDate?.let(::extractYear),
            type = null,
            season = season?.takeIf { it > 0 }?.let { "Сезон $it" },
            episodeCount = null,
            isOngoing = false,
            genres = emptyList(),
        )
    }
}

internal data class AnimeOnImageDto(
    val preview: String? = null,
)

internal data class AnimeOnTranslationsResponseDto(
    val translations: List<AnimeOnTranslationItemDto>? = emptyList(),
)

internal data class AnimeOnTranslationItemDto(
    val translation: AnimeOnTranslationDto? = null,
    val player: List<AnimeOnPlayerDto>? = emptyList(),
)

internal data class AnimeOnTranslationDto(
    val id: Int = 0,
    val name: String? = null,
)

internal data class AnimeOnPlayerDto(
    val id: Int = 0,
    val name: String? = null,
)

internal data class AnimeOnEpisodesResponseDto(
    val episodes: List<AnimeOnEpisodeDto>? = emptyList(),
)

internal data class AnimeOnEpisodeDto(
    val id: Int = 0,
    val episode: Int = 0,
    val poster: String? = null,
    val fileUrl: String? = null,
)

internal data class AnimeOnEpisodeStreamDto(
    val videoUrl: String? = null,
)

internal data class AnimeOnTranslationOption(
    val translationId: Int,
    val playerId: Int,
    val name: String,
    val playerName: String,
)

internal data class AnimeOnEpisodeRef(
    val episodeId: Int,
    val translationId: Int,
    val playerId: Int,
    val translationName: String,
    val playerName: String,
)

internal fun mergeAnimeOnEpisodeSets(
    animeId: Int,
    episodeSets: List<Pair<AnimeOnTranslationOption, List<AnimeOnEpisodeDto>>>,
): List<OnlineEpisode> {
    val groups = linkedMapOf<Int, MutableList<Pair<AnimeOnTranslationOption, AnimeOnEpisodeDto>>>()
    episodeSets.forEach { (translation, episodes) ->
        episodes.forEachIndexed { index, episode ->
            val number = episode.episode.takeIf { it > 0 } ?: index + 1
            groups.getOrPut(number) { mutableListOf() } += translation to episode
        }
    }

    return groups.map { (number, variants) ->
        val streams = variants.mapNotNull { (translation, episode) ->
            val url = episode.fileUrl?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            OnlineStream(
                id = "animeon:$animeId:${translation.translationId}:$number",
                quality = null,
                url = url,
                type = if (url.contains(".m3u8", ignoreCase = true)) OnlineStreamType.HLS else OnlineStreamType.MP4,
                headers = mapOf("Referer" to "https://animeon.club/"),
                translation = translation.name,
                sourceName = "AnimeON · ${translation.playerName}",
            )
        }.distinctBy { it.url to it.translation }

        val lazyReferences = variants.mapNotNull { (translation, episode) ->
            val episodeId = episode.id.takeIf { it > 0 } ?: return@mapNotNull null
            AnimeOnEpisodeRef(
                episodeId = episodeId,
                translationId = translation.translationId,
                playerId = translation.playerId,
                translationName = translation.name,
                playerName = translation.playerName,
            )
        }.distinctBy { listOf(it.episodeId, it.translationId, it.playerId) }

        OnlineEpisode(
            providerId = OnlineProviderIds.ANIME_ON,
            id = "animeon:$animeId:$number",
            releaseId = animeId.toString(),
            ordinal = number.toDouble(),
            name = "$number серия",
            previewUrl = variants.firstNotNullOfOrNull { (_, episode) ->
                episode.poster?.trim()?.takeIf(String::isNotBlank)
            },
            durationMs = 0L,
            sortOrder = number.toDouble(),
            streams = streams,
            sourceRef = encodeAnimeOnEpisodeRefs(lazyReferences),
        )
    }.sortedBy { it.ordinal }
}

internal fun encodeAnimeOnEpisodeRefs(references: List<AnimeOnEpisodeRef>): String? = references
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n") { reference ->
        listOf(
            reference.episodeId.toString(),
            reference.translationId.toString(),
            reference.playerId.toString(),
            java.net.URLEncoder.encode(reference.translationName, Charsets.UTF_8.name()),
            java.net.URLEncoder.encode(reference.playerName, Charsets.UTF_8.name()),
        ).joinToString("|")
    }

internal fun decodeAnimeOnEpisodeRefs(value: String?): List<AnimeOnEpisodeRef> = value
    ?.lineSequence()
    ?.mapNotNull { line ->
        val parts = line.split('|', limit = 5)
        if (parts.size != 5) return@mapNotNull null
        val episodeId = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val translationId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val playerId = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        AnimeOnEpisodeRef(
            episodeId = episodeId,
            translationId = translationId,
            playerId = playerId,
            translationName = java.net.URLDecoder.decode(parts[3], Charsets.UTF_8.name()),
            playerName = java.net.URLDecoder.decode(parts[4], Charsets.UTF_8.name()),
        )
    }
    ?.toList()
    .orEmpty()

private fun String.toAbsoluteAnimeOnUrl(): String? = trim().takeIf(String::isNotBlank)?.let { value ->
    when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> "https://animeon.club$value"
        else -> "https://animeon.club/$value"
    }
}

private fun String?.toAnimeOnImageUrl(): String? = this?.trim()?.takeIf(String::isNotBlank)?.let { value ->
    when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> "https://animeon.club$value"
        else -> "https://animeon.club/api/uploads/images/$value"
    }
}

private fun extractYear(value: String): Int? = YEAR_REGEX.find(value)?.groupValues?.get(1)?.toIntOrNull()

private val YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b", RegexOption.IGNORE_CASE)
