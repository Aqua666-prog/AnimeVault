package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class AnimeThemeClip(
    val url: String,
    val kind: AnimeThemeKind,
    val number: Int?,
    val title: String,
    val artist: String?,
    val resolution: Int?,
    val source: String?,
) {
    val label: String
        get() = buildList {
            val prefix = if (kind == AnimeThemeKind.OPENING) "OP" else "ED"
            add(number?.let { "$prefix$it" } ?: prefix)
            title.takeIf(String::isNotBlank)?.let(::add)
            artist?.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(" · ")
}

/**
 * Resolves high-quality OP/ED video files for a title.
 *
 * The feed asks for [getClip] while the title page can use [getClips] to show
 * every distinct opening/ending. Each theme is reduced to its best available
 * video variant (resolution/source/NC preference), so duplicate encodes do not
 * flood the UI.
 */
class AnimeThemesClipRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val cache = InFlightRequestCache<String, List<AnimeThemeClip>>(
        maxEntries = 96,
        ttlMs = 6 * 60 * 60_000L,
    )

    suspend fun getClip(release: OnlineReleaseDetails): AnimeThemeClip? =
        getClips(release).firstOrNull()

    suspend fun getClips(release: OnlineReleaseDetails): List<AnimeThemeClip> =
        cache.getOrLoad(release.cacheKey()) {
            searchClips(release)
        }

    private suspend fun searchClips(release: OnlineReleaseDetails): List<AnimeThemeClip> {
        val queries = listOfNotNull(
            release.englishName?.trim()?.takeIf(String::isNotBlank),
            release.name.trim().takeIf(String::isNotBlank),
        ).distinct()

        for (query in queries) {
            val url = ANIME_THEMES_ANIME_URL.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("include", INCLUDE)
                .addQueryParameter("page[size]", SEARCH_PAGE_SIZE.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .onlineHeaders(userAgent = animeVaultUserAgent("Android; AnimeThemes clips"))
                .header("Accept", "application/json")
                .build()
            val response = gson.fromJson(
                client.executeText(request, "AnimeThemes clips"),
                ClipEnvelopeDto::class.java,
            ) ?: continue
            val candidates = response.anime.orEmpty()
            val selectedId = chooseAnimeThemesCandidate(
                names = listOfNotNull(release.englishName, release.name),
                year = release.year,
                type = release.type,
                candidates = candidates.map { anime ->
                    AnimeThemesSearchCandidate(
                        animeThemesId = anime.id,
                        titles = buildList {
                            anime.name?.takeIf(String::isNotBlank)?.let(::add)
                            anime.synonyms.orEmpty()
                                .mapNotNullTo(this) { synonym ->
                                    synonym.text?.trim()?.takeIf(String::isNotBlank)
                                }
                        }.distinct(),
                        year = anime.year,
                        type = anime.mediaFormat,
                    )
                },
            ) ?: continue
            val selected = candidates.firstOrNull { it.id == selectedId } ?: continue
            val clips = selected.bestClips()
            if (clips.isNotEmpty()) return clips
        }
        return emptyList()
    }

    private fun ClipAnimeDto.bestClips(): List<AnimeThemeClip> =
        themes.orEmpty()
            .mapNotNull { theme -> theme.bestClip() }
            .distinctBy { clip -> clip.kind to (clip.number ?: clip.title) }
            .sortedWith(
                compareBy<AnimeThemeClip> { if (it.kind == AnimeThemeKind.OPENING) 0 else 1 }
                    .thenBy { it.number ?: Int.MAX_VALUE }
                    .thenBy(AnimeThemeClip::title),
            )

    private fun ClipThemeDto.bestClip(): AnimeThemeClip? {
        val kind = when (type?.uppercase(Locale.ROOT)) {
            "OP" -> AnimeThemeKind.OPENING
            "ED" -> AnimeThemeKind.ENDING
            else -> return null
        }
        val songTitle = song?.title?.trim().orEmpty()
            .ifBlank { slug?.trim().orEmpty() }
            .ifBlank { if (kind == AnimeThemeKind.OPENING) "Opening" else "Ending" }
        val artist = song?.artists.orEmpty()
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" · ")
            .ifBlank { null }
        val resolvedNumber = sequence
            ?: slug?.let(THEME_SEQUENCE_REGEX::find)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return entries.orEmpty()
            .asSequence()
            .filter { entry -> entry.nsfw != true && entry.spoiler != true }
            .flatMap { entry ->
                entry.videos.orEmpty().asSequence().mapNotNull { video ->
                    val link = normalizeVideoLink(video.link) ?: return@mapNotNull null
                    RankedClip(
                        score = videoScore(kind, video),
                        clip = AnimeThemeClip(
                            url = link,
                            kind = kind,
                            number = resolvedNumber,
                            title = songTitle,
                            artist = artist,
                            resolution = video.resolution,
                            source = video.source?.trim()?.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
            .maxByOrNull(RankedClip::score)
            ?.clip
    }

    private fun videoScore(kind: AnimeThemeKind, video: ClipVideoDto): Int {
        val resolutionScore = when {
            (video.resolution ?: 0) >= 1080 -> 14
            (video.resolution ?: 0) >= 720 -> 11
            (video.resolution ?: 0) >= 480 -> 7
            else -> 2
        }
        val sourceScore = when (video.source?.uppercase(Locale.ROOT)) {
            "BD" -> 6
            "WEB" -> 5
            "RAW" -> 3
            else -> 0
        }
        return (if (kind == AnimeThemeKind.OPENING) 12 else 7) +
            resolutionScore +
            sourceScore +
            (if (video.nc == true) 10 else 0) +
            (if (video.subbed != true) 3 else 0) +
            (if (video.lyrics != true) 2 else 0)
    }

    private fun normalizeVideoLink(value: String?): String? {
        val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        return when {
            clean.startsWith("https://") || clean.startsWith("http://") -> clean
            clean.startsWith("/") -> "https://v.animethemes.moe$clean"
            else -> "https://v.animethemes.moe/$clean"
        }
    }

    private fun OnlineReleaseDetails.cacheKey(): String =
        "$providerId|$id|${englishName.orEmpty()}|${year ?: 0}"

    private data class RankedClip(
        val score: Int,
        val clip: AnimeThemeClip,
    )

    private companion object {
        val ANIME_THEMES_ANIME_URL = "https://api.animethemes.moe/anime".toHttpUrl()
        const val INCLUDE =
            "animethemes.song.artists,animethemes.animethemeentries.videos,animesynonyms"
        const val SEARCH_PAGE_SIZE = 15
        val THEME_SEQUENCE_REGEX = Regex("(\\d+)$")
    }
}

private data class ClipEnvelopeDto(
    val anime: List<ClipAnimeDto>? = null,
)

private data class ClipAnimeDto(
    val id: Long,
    val name: String? = null,
    val year: Int? = null,
    @SerializedName("media_format") val mediaFormat: String? = null,
    @SerializedName("animethemes") val themes: List<ClipThemeDto>? = null,
    @SerializedName("animesynonyms") val synonyms: List<ClipSynonymDto>? = null,
)

private data class ClipSynonymDto(
    val text: String? = null,
)

private data class ClipThemeDto(
    val sequence: Int? = null,
    val type: String? = null,
    val slug: String? = null,
    val song: ClipSongDto? = null,
    @SerializedName("animethemeentries") val entries: List<ClipEntryDto>? = null,
)

private data class ClipSongDto(
    val title: String? = null,
    val artists: List<ClipArtistDto>? = null,
)

private data class ClipArtistDto(
    val name: String? = null,
)

private data class ClipEntryDto(
    val nsfw: Boolean? = null,
    val spoiler: Boolean? = null,
    val videos: List<ClipVideoDto>? = null,
)

private data class ClipVideoDto(
    val link: String? = null,
    val resolution: Int? = null,
    val nc: Boolean? = null,
    val subbed: Boolean? = null,
    val lyrics: Boolean? = null,
    val source: String? = null,
)
