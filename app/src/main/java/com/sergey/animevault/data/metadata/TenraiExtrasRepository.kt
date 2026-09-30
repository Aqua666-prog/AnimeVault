package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class TenraiVideo(
    val title: String,
    val youtubeId: String?,
    val url: String?,
    val embedUrl: String?,
    val thumbnailUrl: String?,
    val category: String,
)

data class TenraiVoiceActor(
    val malId: Long?,
    val name: String,
    val imageUrl: String?,
    val language: String?,
)

data class TenraiCharacter(
    val malId: Long,
    val name: String,
    val imageUrl: String?,
    val role: String?,
    val favorites: Int?,
    val voiceActors: List<TenraiVoiceActor>,
)

/**
 * Small client for Tenrai's Jikan-compatible public endpoints.
 *
 * No API key is embedded in the application: public requests are enough for the
 * title/video/character lookups used by AnimeVault. Results are cached so the
 * clip feed does not burn through public rate limits while the user swipes.
 */
class TenraiExtrasRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val videoCache = InFlightRequestCache<Long, List<TenraiVideo>>(
        maxEntries = 128,
        ttlMs = CACHE_TTL_MS,
    )
    private val characterCache = InFlightRequestCache<Long, List<TenraiCharacter>>(
        maxEntries = 64,
        ttlMs = CACHE_TTL_MS,
    )

    suspend fun getVideos(malId: Long): List<TenraiVideo> {
        if (malId <= 0L) return emptyList()
        return videoCache.getOrLoad(malId) {
            val envelope = get("anime/$malId/videos", TenraiVideoEnvelopeDto::class.java)
            buildList {
                envelope.data?.promo.orEmpty().forEach { promo ->
                    promo.trailer?.toVideo(
                        title = promo.title.orEmpty().ifBlank { "PV / трейлер" },
                        category = "promo",
                    )?.let(::add)
                }
                envelope.data?.musicVideos.orEmpty().forEach { music ->
                    music.video?.toVideo(
                        title = music.title.orEmpty()
                            .ifBlank { music.meta?.title.orEmpty() }
                            .ifBlank { "Music video" },
                        category = "music_video",
                    )?.let(::add)
                }
            }.distinctBy { video ->
                video.youtubeId ?: video.url ?: video.embedUrl ?: video.title
            }
        }
    }

    suspend fun getCharacters(malId: Long): List<TenraiCharacter> {
        if (malId <= 0L) return emptyList()
        return characterCache.getOrLoad(malId) {
            val envelope = get("anime/$malId/characters", TenraiCharacterEnvelopeDto::class.java)
            envelope.data.orEmpty().mapNotNull { entry ->
                val character = entry.character ?: return@mapNotNull null
                val id = character.malId ?: return@mapNotNull null
                val name = character.name?.trim().orEmpty().ifBlank { return@mapNotNull null }
                TenraiCharacter(
                    malId = id,
                    name = name,
                    imageUrl = character.images?.webp?.imageUrl?.takeIf(String::isNotBlank)
                        ?: character.images?.jpg?.imageUrl?.takeIf(String::isNotBlank),
                    role = entry.role?.trim()?.takeIf(String::isNotBlank),
                    favorites = entry.favorites,
                    voiceActors = entry.voiceActors.orEmpty().mapNotNull { actor ->
                        val person = actor.person ?: return@mapNotNull null
                        val actorName = person.name?.trim().orEmpty()
                        if (actorName.isBlank()) return@mapNotNull null
                        TenraiVoiceActor(
                            malId = person.malId,
                            name = actorName,
                            imageUrl = person.images?.jpg?.imageUrl?.takeIf(String::isNotBlank),
                            language = actor.language?.trim()?.takeIf(String::isNotBlank),
                        )
                    },
                )
            }
        }
    }

    private fun TenraiVideoDto.toVideo(title: String, category: String): TenraiVideo? {
        val youtubeId = youtubeId?.trim()?.takeIf(String::isNotBlank)
        val url = url?.trim()?.takeIf(String::isNotBlank)
        val embedUrl = embedUrl?.trim()?.takeIf(String::isNotBlank)
        if (youtubeId == null && url == null && embedUrl == null) return null
        return TenraiVideo(
            title = title.trim().ifBlank { "Видео" },
            youtubeId = youtubeId,
            url = url,
            embedUrl = embedUrl,
            thumbnailUrl = images?.mediumImageUrl?.takeIf(String::isNotBlank)
                ?: images?.largeImageUrl?.takeIf(String::isNotBlank)
                ?: images?.imageUrl?.takeIf(String::isNotBlank)
                ?: images?.smallImageUrl?.takeIf(String::isNotBlank),
            category = category,
        )
    }

    private suspend fun <T> get(path: String, clazz: Class<T>): T {
        val url = TENRAI_BASE_URL.newBuilder()
            .addPathSegments(path)
            .build()
        val request = Request.Builder()
            .url(url)
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; Tenrai extras"))
            .header("Accept", "application/json")
            .build()
        return gson.fromJson(client.executeText(request, "Tenrai"), clazz)
            ?: error("Tenrai вернул пустой ответ")
    }

    private companion object {
        val TENRAI_BASE_URL = "https://api.tenrai.org/v1/".toHttpUrl()
        const val CACHE_TTL_MS = 6 * 60 * 60_000L
    }
}

private data class TenraiVideoEnvelopeDto(
    val data: TenraiVideoDataDto? = null,
)

private data class TenraiVideoDataDto(
    val promo: List<TenraiPromoDto>? = null,
    @SerializedName("music_videos") val musicVideos: List<TenraiMusicVideoDto>? = null,
)

private data class TenraiPromoDto(
    val title: String? = null,
    val trailer: TenraiVideoDto? = null,
)

private data class TenraiMusicVideoDto(
    val title: String? = null,
    val video: TenraiVideoDto? = null,
    val meta: TenraiMusicMetaDto? = null,
)

private data class TenraiMusicMetaDto(
    val title: String? = null,
    val author: String? = null,
)

private data class TenraiVideoDto(
    @SerializedName("youtube_id") val youtubeId: String? = null,
    val url: String? = null,
    @SerializedName("embed_url") val embedUrl: String? = null,
    val images: TenraiVideoImagesDto? = null,
)

private data class TenraiVideoImagesDto(
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("small_image_url") val smallImageUrl: String? = null,
    @SerializedName("medium_image_url") val mediumImageUrl: String? = null,
    @SerializedName("large_image_url") val largeImageUrl: String? = null,
)

private data class TenraiCharacterEnvelopeDto(
    val data: List<TenraiCharacterEntryDto>? = null,
)

private data class TenraiCharacterEntryDto(
    val character: TenraiCharacterDto? = null,
    val role: String? = null,
    val favorites: Int? = null,
    @SerializedName("voice_actors") val voiceActors: List<TenraiVoiceActorEntryDto>? = null,
)

private data class TenraiCharacterDto(
    @SerializedName("mal_id") val malId: Long? = null,
    val name: String? = null,
    val images: TenraiCharacterImagesDto? = null,
)

private data class TenraiCharacterImagesDto(
    val jpg: TenraiImageDto? = null,
    val webp: TenraiImageDto? = null,
)

private data class TenraiVoiceActorEntryDto(
    val person: TenraiPersonDto? = null,
    val language: String? = null,
)

private data class TenraiPersonDto(
    @SerializedName("mal_id") val malId: Long? = null,
    val name: String? = null,
    val images: TenraiPersonImagesDto? = null,
)

private data class TenraiPersonImagesDto(
    val jpg: TenraiImageDto? = null,
)

private data class TenraiImageDto(
    @SerializedName("image_url") val imageUrl: String? = null,
)
