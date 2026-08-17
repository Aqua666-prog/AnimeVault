package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Загружает названия опенингов и эндингов напрямую из AnimeThemes.
 *
 * Поиск выполняется по английскому/ромадзи-названию и проверяется по названию,
 * синонимам, году и формату. Блок «Музыка» больше не зависит от Jikan и
 * нестабильного парсинга страниц MyAnimeList.
 */
class AnimeThemeRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, AnimeThemeInfo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimeThemeInfo>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun getThemes(release: OnlineReleaseDetails): AnimeThemeInfo = lock.withLock {
        val cacheKey = release.cacheKey()
        cache[cacheKey]?.let { return@withLock it }

        val result = searchAnimeThemes(release) ?: emptyThemeInfo(release)
        cache[cacheKey] = result
        result
    }

    private suspend fun searchAnimeThemes(release: OnlineReleaseDetails): AnimeThemeInfo? {
        val queries = listOfNotNull(
            release.englishName?.trim()?.takeIf(String::isNotBlank),
            release.name.trim().takeIf(String::isNotBlank),
        ).distinct()

        for (query in queries) {
            val url = ANIME_THEMES_ANIME_URL.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("include", ANIME_THEMES_INCLUDE)
                .addQueryParameter("page[size]", SEARCH_PAGE_SIZE.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .onlineHeaders(userAgent = animeVaultUserAgent("Android; AnimeThemes metadata"))
                .header("Accept", "application/json")
                .build()
            val json = client.executeText(request, "AnimeThemes")
            val candidates = gson.fromJson(json, AnimeThemesEnvelopeDto::class.java)
                ?.anime
                .orEmpty()
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
                                .mapNotNullTo(this) { it.text?.takeIf(String::isNotBlank) }
                        }.distinct(),
                        year = anime.year,
                        type = anime.mediaFormat,
                    )
                },
            ) ?: continue
            val selected = candidates.firstOrNull { it.id == selectedId } ?: continue
            return selected.toThemeInfo(release.externalIds.malId ?: 0L)
        }
        return null
    }

    private fun AnimeThemesAnimeDto.toThemeInfo(malId: Long): AnimeThemeInfo {
        val songs = themes.orEmpty().mapNotNull { theme ->
            val kind = when (theme.type?.uppercase(Locale.ROOT)) {
                "OP" -> AnimeThemeKind.OPENING
                "ED" -> AnimeThemeKind.ENDING
                else -> return@mapNotNull null
            }
            val title = theme.song?.title?.trim().orEmpty()
                .ifBlank { theme.slug?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }
            val artist = theme.song?.artists.orEmpty()
                .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(" · ")
                .ifBlank { null }
            val episodeRange = theme.entries.orEmpty()
                .mapNotNull { it.episodes?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(", ")
                .takeIf(String::isNotBlank)
                ?.let(::localizeAnimeThemesEpisodes)
            val number = theme.sequence
                ?: theme.slug?.let(THEME_SEQUENCE_REGEX::find)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            AnimeThemeSong(
                kind = kind,
                number = number,
                title = title,
                artist = artist,
                episodeRange = episodeRange,
                raw = listOfNotNull(title, artist?.let { "by $it" }).joinToString(" "),
            )
        }
        return AnimeThemeInfo(
            malId = malId,
            openings = songs.filter { it.kind == AnimeThemeKind.OPENING }
                .distinctBy { it.number to it.title }
                .sortedWith(compareBy<AnimeThemeSong> { it.number ?: Int.MAX_VALUE }.thenBy { it.title }),
            endings = songs.filter { it.kind == AnimeThemeKind.ENDING }
                .distinctBy { it.number to it.title }
                .sortedWith(compareBy<AnimeThemeSong> { it.number ?: Int.MAX_VALUE }.thenBy { it.title }),
            sourceLabel = "AnimeThemes",
        )
    }

    private fun emptyThemeInfo(release: OnlineReleaseDetails): AnimeThemeInfo = AnimeThemeInfo(
        malId = release.externalIds.malId ?: 0L,
        openings = emptyList(),
        endings = emptyList(),
        sourceLabel = "AnimeThemes",
    )

    private fun OnlineReleaseDetails.cacheKey(): String = buildString {
        append(providerId).append('|').append(id)
        append('|').append(englishName.orEmpty())
        append('|').append(year ?: 0)
    }

    private companion object {
        val ANIME_THEMES_ANIME_URL = "https://api.animethemes.moe/anime".toHttpUrl()
        const val ANIME_THEMES_INCLUDE =
            "animethemes.song.artists,animethemes.animethemeentries,animesynonyms"
        const val SEARCH_PAGE_SIZE = 15
        const val MAX_CACHE_ENTRIES = 80
        val THEME_SEQUENCE_REGEX = Regex("(\\d+)$")
    }
}

internal fun localizeAnimeThemesEpisodes(value: String): String =
    "серии " + value.replace(Regex("(?<=\\d)-(?=\\d|$)"), "–")

private data class AnimeThemesEnvelopeDto(
    val anime: List<AnimeThemesAnimeDto>? = null,
)

private data class AnimeThemesAnimeDto(
    val id: Long,
    val name: String? = null,
    val year: Int? = null,
    @SerializedName("media_format") val mediaFormat: String? = null,
    @SerializedName("animethemes") val themes: List<AnimeThemesThemeDto>? = null,
    @SerializedName("animesynonyms") val synonyms: List<AnimeThemesSynonymDto>? = null,
)

private data class AnimeThemesSynonymDto(
    val text: String? = null,
)

private data class AnimeThemesThemeDto(
    val sequence: Int? = null,
    val slug: String? = null,
    val type: String? = null,
    val song: AnimeThemesSongDto? = null,
    @SerializedName("animethemeentries") val entries: List<AnimeThemesEntryDto>? = null,
)

private data class AnimeThemesSongDto(
    val title: String? = null,
    val artists: List<AnimeThemesArtistDto>? = null,
)

private data class AnimeThemesArtistDto(
    val name: String? = null,
)

private data class AnimeThemesEntryDto(
    val episodes: String? = null,
)
