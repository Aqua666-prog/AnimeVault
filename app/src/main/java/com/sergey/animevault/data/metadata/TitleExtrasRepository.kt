package com.sergey.animevault.data.metadata

import com.sergey.animevault.data.cache.InFlightRequestCache
import com.sergey.animevault.data.animetka.AnimetkaExtrasRepository
import com.sergey.animevault.data.animetka.AnimetkaTrailerDto
import com.sergey.animevault.data.animetka.ANIMETKA_ORIGIN
import com.sergey.animevault.data.animetka.ANIMETKA_REFERER
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class TitleExtraVideoKind {
    TRAILER,
    PROMO,
    OPENING,
    ENDING,
    MUSIC,
    CLIP,
    EPISODE_PREVIEW,
    OTHER,
}

data class TitleExtraVideo(
    val id: String,
    val title: String,
    val kind: TitleExtraVideoKind,
    val source: String,
    val thumbnailUrl: String? = null,
    val directUrl: String? = null,
    val youtubeId: String? = null,
    val embedUrl: String? = null,
    val externalUrl: String? = null,
    val qualityLabel: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val isPlayableInApp: Boolean
        get() = !directUrl.isNullOrBlank() || !youtubeId.isNullOrBlank() || !embedUrl.isNullOrBlank()

    val kindLabel: String
        get() = when (kind) {
            TitleExtraVideoKind.TRAILER -> "Трейлер"
            TitleExtraVideoKind.PROMO -> "PV / промо"
            TitleExtraVideoKind.OPENING -> "Опенинг"
            TitleExtraVideoKind.ENDING -> "Эндинг"
            TitleExtraVideoKind.MUSIC -> "Музыкальное видео"
            TitleExtraVideoKind.CLIP -> "Клип"
            TitleExtraVideoKind.EPISODE_PREVIEW -> "Превью серии"
            TitleExtraVideoKind.OTHER -> "Видео"
        }
}

data class TitleVoiceActor(
    val malId: Long?,
    val name: String,
    val imageUrl: String?,
    val language: String?,
)

data class TitleCharacter(
    val id: String,
    val malId: Long?,
    val name: String,
    val russianName: String?,
    val japaneseName: String?,
    val aliases: List<String>,
    val imageUrl: String?,
    val role: String,
    val description: String?,
    val voiceActors: List<TitleVoiceActor>,
    val popularity: Int? = null,
    val shikimoriId: Long? = null,
    val source: String = "Tenrai",
) {
    val displayName: String
        get() = russianName?.takeIf(String::isNotBlank) ?: name

    val isMain: Boolean
        get() {
            val normalized = role.lowercase(Locale.ROOT)
            return "main" in normalized || "глав" in normalized
        }
}

data class TitleExtras(
    val malId: Long?,
    val shikimoriId: Long?,
    val characters: List<TitleCharacter>,
    val videos: List<TitleExtraVideo>,
    val sources: List<String>,
    val tenraiOverview: TenraiAnimeOverview? = null,
    val tenraiStaff: List<TenraiStaffMember> = emptyList(),
    val tenraiRecommendations: List<TenraiRecommendation> = emptyList(),
    val tenraiStatistics: TenraiAnimeStatistics? = null,
    val tenraiEpisodes: List<TenraiEpisodeMetadata> = emptyList(),
    val tenraiPictures: List<TenraiPicture> = emptyList(),
    val tenraiHealth: TenraiHealthSnapshot? = null,
    val charactersError: String? = null,
    val charactersAreStale: Boolean = false,
    val hasLoadErrors: Boolean = false,
)

/**
 * Aggregates complementary metadata/video sources used by AnimeVault:
 * AnimeThemes for direct OP/ED WebM, Tenrai for MAL videos + seiyuu, and
 * Shikimori for Russian character descriptions, with verified Animetka fallback.
 */
class TitleExtrasRepository(
    private val animeThemes: AnimeThemesClipRepository,
    private val tenrai: TenraiExtrasRepository,
    private val shikimori: ShikimoriExtrasRepository,
    private val animetka: AnimetkaExtrasRepository? = null,
    private val tenraiMetadata: TenraiMetadataRepository? = null,
) {
    private val extrasCache = InFlightRequestCache<String, TitleExtras>(
        maxEntries = 64,
        ttlMs = EXTRAS_CACHE_TTL_MS,
    )

    // Keep the last successful characters during failed refreshes, bounded like the extras cache.
    private val lastCharacters = object : LinkedHashMap<String, List<TitleCharacter>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TitleCharacter>>?): Boolean =
            size > 64
    }

    /** Lightweight path used by the vertical feed. It never downloads characters. */
    suspend fun getBestFeedVideo(release: OnlineReleaseDetails): TitleExtraVideo? {
        sourceOrNull { animeThemes.getClip(release) }
            ?.toTitleVideo()
            ?.let { return it }
        animetka?.let { repository ->
            sourceOrNull { repository.trailersFor(release) }
                ?.firstOrNull()
                ?.toTitleVideo()
                ?.let { return it }
        }

        var shiki: ShikimoriAnimeExtras? = null
        var malId = release.externalIds.malId
        if (malId == null) {
            shiki = sourceOrNull { shikimori.getVideos(release) }
            malId = shiki?.malId
        }

        val tenraiVideos = if (malId != null) {
            sourceOrNull { tenrai.getVideos(malId) }.orEmpty()
        } else {
            emptyList()
        }
        tenraiVideos
            .map { it.toTitleVideo() }
            .filter(::isFeedPlayable)
            .maxByOrNull(::feedFallbackScore)
            ?.takeIf { it.kind != TitleExtraVideoKind.MUSIC }
            ?.let { return it }

        if (shiki == null) shiki = sourceOrNull { shikimori.getVideos(release) }
        shiki?.videos.orEmpty()
            .map { it.toTitleVideo() }
            .filter { it.isPlayableInApp && !it.youtubeId.isNullOrBlank() }
            .maxByOrNull(::feedFallbackScore)
            ?.let { return it }

        return tenraiVideos
            .map { it.toTitleVideo() }
            .filter(::isFeedPlayable)
            .maxByOrNull(::feedFallbackScore)
    }

    suspend fun getExtras(release: OnlineReleaseDetails, forceRefresh: Boolean = false): TitleExtras =
        extrasCache.getOrLoad(release.cacheKey(), forceRefresh = forceRefresh, cacheIf = { !it.hasLoadErrors }) {
            val failures = AtomicBoolean(false)
            suspend fun <T> loadSource(block: suspend () -> T): T? = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures.set(true)
                null
            }
            coroutineScope {
                val themesDeferred = async {
                    loadSource { animeThemes.getClips(release) }.orEmpty()
                }
                val shikiDeferred = async {
                    loadSource { shikimori.getExtras(release, forceRefresh = forceRefresh) }
                }
                val animetkaDeferred = async {
                    animetka?.let { loadSource { it.trailersFor(release) } }.orEmpty()
                }

                val shikiExtras = shikiDeferred.await()
                val fallbackCharactersDeferred = async {
                    if (shikiExtras?.characters.isNullOrEmpty()) {
                        animetka?.let { loadSource { it.charactersFor(release) } }
                    } else null
                }
                val malId = release.externalIds.malId ?: shikiExtras?.malId
                val tenraiVideosDeferred = async {
                    if (malId == null) emptyList() else loadSource { tenrai.getVideos(malId) }.orEmpty()
                }
                val tenraiCharactersDeferred = async {
                    if (malId == null) null else loadSource { tenrai.getCharacters(malId, forceRefresh = forceRefresh) }
                }
                val tenraiMetadataDeferred = async {
                    if (malId == null || tenraiMetadata == null) {
                        null
                    } else {
                        loadSource { tenraiMetadata.loadBundle(malId) }
                    }
                }
                val tenraiEpisodesDeferred = async {
                    if (malId == null || tenraiMetadata == null) {
                        emptyList()
                    } else {
                        loadSource { tenraiMetadata.getEpisodesPage(malId, page = 1).items }.orEmpty()
                    }
                }
                val tenraiPicturesDeferred = async {
                    if (malId == null || tenraiMetadata == null) {
                        emptyList()
                    } else {
                        loadSource { tenraiMetadata.getPictures(malId) }.orEmpty()
                    }
                }

                val themeVideos = themesDeferred.await().map { it.toTitleVideo() }
                val tenraiVideos = tenraiVideosDeferred.await().map { it.toTitleVideo() }
                val shikiVideos = shikiExtras?.videos.orEmpty().map { it.toTitleVideo() }
                val animetkaVideos = animetkaDeferred.await().map { it.toTitleVideo() }
                val videos = (themeVideos + animetkaVideos + tenraiVideos + shikiVideos)
                    .filter(TitleExtraVideo::isPlayableInApp)
                    .distinctBy(::videoDedupKey)
                    .sortedWith(
                        compareBy<TitleExtraVideo> { titleVideoSortRank(it.kind) }
                            .thenBy(TitleExtraVideo::title),
                    )

                val tenraiCharactersResult = tenraiCharactersDeferred.await()
                val tenraiCharacters = tenraiCharactersResult.orEmpty()
                val fallbackCharacters = fallbackCharactersDeferred.await()
                val characterSourceSucceeded = shikiExtras != null || fallbackCharacters != null || !tenraiCharactersResult.isNullOrEmpty()
                val tenraiBundle = tenraiMetadataDeferred.await()
                val tenraiEpisodes = tenraiEpisodesDeferred.await()
                val tenraiPictures = tenraiPicturesDeferred.await()
                val loadedCharacters = mergeCharacters(
                    shikimoriCharacters = shikiExtras?.characters?.takeIf { it.isNotEmpty() } ?: fallbackCharacters.orEmpty(),
                    tenraiCharacters = tenraiCharacters,
                )

                val characters = synchronized(lastCharacters) {
                    if (characterSourceSucceeded) {
                        lastCharacters[release.cacheKey()] = loadedCharacters
                        loadedCharacters
                    } else lastCharacters[release.cacheKey()].orEmpty()
                }

                TitleExtras(
                    malId = malId,
                    shikimoriId = shikiExtras?.shikimoriId ?: release.externalIds.shikimoriId,
                    characters = characters,
                    videos = videos,
                    sources = buildList {
                        if (themeVideos.isNotEmpty()) add("AnimeThemes")
                        if (animetkaVideos.isNotEmpty() || characters.any { it.source == "Аниметка" }) add("Аниметка")
                        val hasNativeTenrai = tenraiVideos.isNotEmpty() ||
                            tenraiCharacters.isNotEmpty() ||
                            tenraiBundle?.staff?.isNotEmpty() == true ||
                            tenraiBundle?.recommendations?.isNotEmpty() == true ||
                            tenraiBundle?.statistics != null
                        if (hasNativeTenrai) add("Tenrai")
                        tenraiBundle?.overview?.let { overview ->
                            val sourceLabel = if (overview.isStale) {
                                "${overview.metadataSource} (кэш)"
                            } else {
                                overview.metadataSource
                            }
                            if (sourceLabel != "Tenrai" || !hasNativeTenrai) add(sourceLabel)
                        }
                        if (shikiExtras != null || characters.any { it.source == "Shikimori" }) add("Shikimori")
                    }.distinct(),
                    tenraiOverview = tenraiBundle?.overview,
                    tenraiStaff = tenraiBundle?.staff.orEmpty(),
                    tenraiRecommendations = tenraiBundle?.recommendations.orEmpty(),
                    tenraiStatistics = tenraiBundle?.statistics,
                    tenraiEpisodes = tenraiEpisodes,
                    tenraiPictures = tenraiPictures,
                    tenraiHealth = tenraiMetadata?.healthSnapshot(),
                    charactersError = if (characterSourceSucceeded) null else
                        "Не удалось загрузить персонажей. Проверьте соединение и повторите загрузку.",
                    charactersAreStale = !characterSourceSucceeded && characters.isNotEmpty(),
                    hasLoadErrors = failures.get() || !characterSourceSucceeded,
                )
            }
        }

    suspend fun getCharacterDetails(character: TitleCharacter): TitleCharacter {
        if (character.source != "Аниметка" || character.shikimoriId == null) return character
        val repository = checkNotNull(animetka) { "Аниметка недоступна" }
        val details = repository.characterDetails(character.shikimoriId.toString())
        check(character.malId == null || details.malId == null || character.malId == details.malId) {
            "Аниметка вернула противоречивый MAL ID персонажа"
        }
        return character.copy(
            malId = details.malId ?: character.malId,
            name = details.name,
            russianName = details.russianName ?: character.russianName,
            japaneseName = details.japaneseName ?: character.japaneseName,
            aliases = details.synonyms.ifEmpty { character.aliases },
            imageUrl = details.imageUrl ?: character.imageUrl,
            description = details.description ?: character.description,
            // The role belongs to this title, not the global character card / other appearances.
        )
    }

    private fun mergeCharacters(
        shikimoriCharacters: List<ShikimoriCharacter>,
        tenraiCharacters: List<TenraiCharacter>,
    ): List<TitleCharacter> {
        val tenraiByMalId = tenraiCharacters.associateBy(TenraiCharacter::malId)
        val matchedTenraiIds = mutableSetOf<Long>()
        val merged = shikimoriCharacters.map { shiki ->
            val tenraiCharacter = shiki.malId?.let(tenraiByMalId::get)
            tenraiCharacter?.malId?.let(matchedTenraiIds::add)
            TitleCharacter(
                id = "shiki:${shiki.id}",
                shikimoriId = shiki.id.toLongOrNull(),
                source = shiki.source,
                malId = shiki.malId,
                name = shiki.name,
                russianName = shiki.russianName,
                japaneseName = shiki.japaneseName,
                aliases = shiki.synonyms,
                imageUrl = shiki.imageUrl ?: tenraiCharacter?.imageUrl,
                role = localizeRole(shiki.roleRu ?: shiki.roleEn ?: tenraiCharacter?.role),
                description = shiki.description,
                voiceActors = tenraiCharacter?.voiceActors.orEmpty().toTitleVoiceActors(),
                popularity = tenraiCharacter?.favorites,
            )
        }.toMutableList()

        tenraiCharacters
            .asSequence()
            .filterNot { it.malId in matchedTenraiIds }
            .mapTo(merged) { character ->
                TitleCharacter(
                    id = "mal:${character.malId}",
                    malId = character.malId,
                    name = character.name,
                    russianName = null,
                    japaneseName = null,
                    aliases = emptyList(),
                    imageUrl = character.imageUrl,
                    role = localizeRole(character.role),
                    description = null,
                    voiceActors = character.voiceActors.toTitleVoiceActors(),
                    popularity = character.favorites,
                )
            }

        return merged
            .distinctBy(TitleCharacter::id)
            .sortedWith(
                compareByDescending<TitleCharacter>(TitleCharacter::isMain)
                    .thenByDescending { it.popularity ?: 0 }
                    .thenBy(TitleCharacter::displayName),
            )
    }

    private fun List<TenraiVoiceActor>.toTitleVoiceActors(): List<TitleVoiceActor> =
        sortedWith(
            compareByDescending<TenraiVoiceActor> { it.language.equals("Japanese", ignoreCase = true) }
                .thenBy(TenraiVoiceActor::name),
        )
            .distinctBy { actor -> actor.malId ?: normalizePersonName(actor.name) }
            .take(MAX_VOICE_ACTORS)
            .map { actor ->
                TitleVoiceActor(
                    malId = actor.malId,
                    name = actor.name,
                    imageUrl = actor.imageUrl,
                    language = actor.language,
                )
            }

    private fun AnimeThemeClip.toTitleVideo(): TitleExtraVideo = TitleExtraVideo(
        id = "animethemes:${url.hashCode()}",
        title = label,
        kind = if (kind == AnimeThemeKind.OPENING) {
            TitleExtraVideoKind.OPENING
        } else {
            TitleExtraVideoKind.ENDING
        },
        source = "AnimeThemes",
        directUrl = url,
        externalUrl = url,
        qualityLabel = resolution?.let { "${it}p" },
    )

    private fun TenraiVideo.toTitleVideo(): TitleExtraVideo {
        val normalizedYoutubeId = youtubeId?.takeIf(String::isNotBlank)
            ?: extractYoutubeId(url)
            ?: extractYoutubeId(embedUrl)
        return TitleExtraVideo(
            id = "tenrai:${normalizedYoutubeId ?: url ?: embedUrl ?: title}",
            title = title,
            kind = if (category == "promo") TitleExtraVideoKind.TRAILER else TitleExtraVideoKind.MUSIC,
            source = "Tenrai",
            thumbnailUrl = thumbnailUrl,
            youtubeId = normalizedYoutubeId,
            embedUrl = embedUrl,
            externalUrl = url,
        )
    }

    private fun ShikimoriVideo.toTitleVideo(): TitleExtraVideo {
        val youtubeId = extractYoutubeId(playerUrl) ?: extractYoutubeId(url)
        return TitleExtraVideo(
            id = "shikimori:$id",
            title = name?.takeIf(String::isNotBlank) ?: shikimoriKindLabel(kind),
            kind = shikimoriVideoKind(kind),
            source = "Shikimori",
            thumbnailUrl = imageUrl,
            youtubeId = youtubeId,
            embedUrl = playerUrl,
            externalUrl = url,
        )
    }

    private fun AnimetkaTrailerDto.toTitleVideo(): TitleExtraVideo = TitleExtraVideo(
        id = "animetka:$materialId:$number",
        title = "Трейлер $number",
        kind = TitleExtraVideoKind.TRAILER,
        source = "Аниметка",
        thumbnailUrl = previewUrl,
        directUrl = url,
        externalUrl = url,
        headers = mapOf("Referer" to ANIMETKA_REFERER, "Origin" to ANIMETKA_ORIGIN),
    )

    private fun OnlineReleaseDetails.cacheKey(): String = buildString {
        append(providerId).append('|').append(id)
        append('|').append(externalIds.malId ?: 0L)
        append('|').append(externalIds.shikimoriId ?: 0L)
        append('|').append(externalIds.anilistId ?: 0L)
        append('|').append(name)
        append('|').append(englishName.orEmpty())
        append('|').append(year ?: 0)
    }

    private companion object {
        const val EXTRAS_CACHE_TTL_MS = 2 * 60 * 60_000L
        const val MAX_VOICE_ACTORS = 6
    }
}

private suspend fun <T> sourceOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private fun isFeedPlayable(video: TitleExtraVideo): Boolean =
    !video.directUrl.isNullOrBlank() || !video.youtubeId.isNullOrBlank()

private fun feedFallbackScore(video: TitleExtraVideo): Int = when (video.kind) {
    TitleExtraVideoKind.TRAILER -> 100
    TitleExtraVideoKind.PROMO -> 95
    TitleExtraVideoKind.OPENING -> 90
    TitleExtraVideoKind.CLIP -> 85
    TitleExtraVideoKind.EPISODE_PREVIEW -> 80
    TitleExtraVideoKind.ENDING -> 70
    TitleExtraVideoKind.MUSIC -> 60
    TitleExtraVideoKind.OTHER -> 40
} + if (!video.youtubeId.isNullOrBlank()) 10 else 0

private fun titleVideoSortRank(kind: TitleExtraVideoKind): Int = when (kind) {
    TitleExtraVideoKind.TRAILER -> 0
    TitleExtraVideoKind.PROMO -> 1
    TitleExtraVideoKind.OPENING -> 2
    TitleExtraVideoKind.ENDING -> 3
    TitleExtraVideoKind.MUSIC -> 4
    TitleExtraVideoKind.CLIP -> 5
    TitleExtraVideoKind.EPISODE_PREVIEW -> 6
    TitleExtraVideoKind.OTHER -> 7
}

private fun videoDedupKey(video: TitleExtraVideo): String = when {
    !video.youtubeId.isNullOrBlank() -> "yt:${video.youtubeId}"
    !video.directUrl.isNullOrBlank() -> "direct:${video.directUrl}"
    !video.embedUrl.isNullOrBlank() -> "embed:${video.embedUrl}"
    else -> "${video.source}:${video.title.lowercase(Locale.ROOT)}"
}

private fun shikimoriVideoKind(value: String): TitleExtraVideoKind = when (value.lowercase(Locale.ROOT)) {
    "pv", "character_trailer", "trailer" -> TitleExtraVideoKind.TRAILER
    "cm" -> TitleExtraVideoKind.PROMO
    "op" -> TitleExtraVideoKind.OPENING
    "ed" -> TitleExtraVideoKind.ENDING
    "op_ed_clip", "clip" -> TitleExtraVideoKind.CLIP
    "episode_preview" -> TitleExtraVideoKind.EPISODE_PREVIEW
    else -> TitleExtraVideoKind.OTHER
}

private fun shikimoriKindLabel(value: String): String = when (shikimoriVideoKind(value)) {
    TitleExtraVideoKind.TRAILER -> "Трейлер"
    TitleExtraVideoKind.PROMO -> "Промо"
    TitleExtraVideoKind.OPENING -> "Опенинг"
    TitleExtraVideoKind.ENDING -> "Эндинг"
    TitleExtraVideoKind.CLIP -> "Клип"
    TitleExtraVideoKind.EPISODE_PREVIEW -> "Превью серии"
    TitleExtraVideoKind.MUSIC -> "Музыкальное видео"
    TitleExtraVideoKind.OTHER -> "Видео"
}

private fun localizeRole(value: String?): String {
    val clean = value?.trim().orEmpty()
    if (clean.isBlank()) return "Персонаж"
    return when (clean.lowercase(Locale.ROOT)) {
        "main" -> "Главный персонаж"
        "supporting" -> "Второстепенный персонаж"
        "background" -> "Эпизодический персонаж"
        else -> clean
    }
}

private fun normalizePersonName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun extractYoutubeId(value: String?): String? {
    val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val patterns = listOf(
        Regex("(?:youtube(?:-nocookie)?\\.com/(?:watch\\?v=|embed/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{6,})", RegexOption.IGNORE_CASE),
        Regex("[?&]v=([A-Za-z0-9_-]{6,})", RegexOption.IGNORE_CASE),
    )
    return patterns.firstNotNullOfOrNull { regex ->
        regex.find(clean)?.groupValues?.getOrNull(1)
    }
}
