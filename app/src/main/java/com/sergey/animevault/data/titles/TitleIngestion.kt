package com.sergey.animevault.data.titles

import com.sergey.animevault.data.online.*
import com.sergey.animevault.data.metadata.*

fun ExternalAnimeIds.titleKeys() = listOfNotNull(
    animeIdKey("anilist", anilistId), animeIdKey("mal", malId), animeIdKey("shikimori", shikimoriId),
)
fun AnimeTitleStore.accept(card: OnlineReleaseCard) = put(
    providerTitleKey(card.providerId, card.id), card.titleNames, card.externalIds.titleKeys().takeIf { it.isNotEmpty() },
)
fun AnimeTitleStore.accept(release: OnlineReleaseDetails) = put(
    providerTitleKey(release.providerId, release.id), release.titleNames, release.externalIds.titleKeys().takeIf { it.isNotEmpty() },
)
fun AnimeTitleStore.accept(candidate: AniListMetadataCandidate) {
    val names = AnimeNames(name = candidate.canonicalTitle, english = candidate.englishTitle,
        japanese = candidate.nativeTitle, synonyms = candidate.synonyms)
    put("anilist:${candidate.anilistId}", names)
    candidate.malId?.let { put("mal:$it", names) }
}
fun AnimeTitleStore.accept(anime: ShikimoriAnimeExtras) {
    val names = AnimeNames(anime.russianName, anime.name, anime.englishName, anime.japaneseName, anime.synonyms)
    anime.shikimoriId?.let { put("shikimori:$it", names) }
    anime.malId?.let { put("mal:$it", names) }
}

fun org.json.JSONObject.animeNames(): AnimeNames = AnimeNames(
    russian = AnimeNames.clean(optString("russian")), name = AnimeNames.clean(optString("name")),
    english = AnimeNames.clean(optString("english")), japanese = AnimeNames.clean(optString("japanese")),
    synonyms = optJSONArray("synonyms")?.let { array ->
        (0 until array.length()).mapNotNull { AnimeNames.clean(array.optString(it)) }
    }.orEmpty(),
)
