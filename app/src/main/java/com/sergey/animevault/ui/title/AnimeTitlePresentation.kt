package com.sergey.animevault.ui.title

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.sergey.animevault.data.titles.*
import com.sergey.animevault.data.online.*
import com.sergey.animevault.data.model.*
import com.sergey.animevault.data.metadata.*
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.db.AnimeTitleEntity
import com.sergey.animevault.ui.home.HomeContinueItem
import com.sergey.animevault.ui.shikimori.ImportedShikiAnime

/** Presentation only. Never pass its output back into identity, source matching or search. */
data class AnimeTitleSubject(val original: String, val keys: List<String>, val names: AnimeNames = AnimeNames.original(original))
data class AnimeTitlePresentation(
    val language: AnimeTitleLanguage = AnimeTitleLanguage.RUSSIAN,
    val index: AnimeTitleIndex = AnimeTitleIndex(),
    val setLanguage: (AnimeTitleLanguage) -> Unit = {},
) {
    fun display(subject: AnimeTitleSubject) = AnimeTitleResolver.resolve(
        index.namesFor(subject.keys, subject.names), subject.original, language,
    )
}
val LocalAnimeTitles = staticCompositionLocalOf { AnimeTitlePresentation() }

fun animeTitleSubject(value: Any): AnimeTitleSubject = when (value) {
    is OnlineReleaseCard -> AnimeTitleSubject(value.name, listOf(providerTitleKey(value.providerId, value.id)) + value.externalIds.titleKeys(), value.titleNames)
    is OnlineReleaseDetails -> AnimeTitleSubject(value.name, listOf(providerTitleKey(value.providerId, value.id)) + value.externalIds.titleKeys(), value.titleNames)
    is OnlineLibraryEntry -> AnimeTitleSubject(value.name, listOf(providerTitleKey(value.providerId, value.releaseId)), AnimeNames.original(value.name, value.englishName))
    is com.sergey.animevault.ui.library.LibraryHubItem -> AnimeTitleSubject(value.title, listOfNotNull(
        value.localId?.let(::localTitleKey), value.providerId?.let { providerTitleKey(it, value.releaseId.orEmpty()) }))
    is LibraryTitleRow -> AnimeTitleSubject(value.name, listOf(localTitleKey(value.id)))
    is AnimeTitleEntity -> AnimeTitleSubject(value.name, listOf(localTitleKey(value.id)))
    is LocalHistoryRow -> AnimeTitleSubject(value.titleName, listOf(localTitleKey(value.titleId)))
    is HomeContinueItem.Local -> AnimeTitleSubject(value.title, listOf(localTitleKey(value.titleId)))
    is HomeContinueItem.Online -> AnimeTitleSubject(value.title, listOf(providerTitleKey(value.providerId, value.releaseId)))
    is DownloadEntry -> AnimeTitleSubject(value.releaseName, listOf(providerTitleKey(value.providerId, value.releaseId)))
    is TenraiCatalogItem -> AnimeTitleSubject(value.title, listOfNotNull(
        if (value.fallbackAniListId != null) animeIdKey("anilist", value.fallbackAniListId) else animeIdKey("mal", value.malId)),
        AnimeNames(name = value.title, english = value.englishTitle, japanese = value.japaneseTitle))
    is TenraiRecommendation -> AnimeTitleSubject(value.title, listOf("mal:${value.malId}"))
    is TenraiRelationItem -> AnimeTitleSubject(value.name, listOf("mal:${value.malId}"))
    is AniListMetadataCandidate -> AnimeTitleSubject(value.canonicalTitle, listOf("anilist:${value.anilistId}"),
        AnimeNames(name = value.canonicalTitle, english = value.englishTitle, japanese = value.nativeTitle, synonyms = value.synonyms))
    is VaultAiringEpisode -> AnimeTitleSubject(value.searchTitle.ifBlank { value.title },
        listOfNotNull("anilist:${value.mediaId}", animeIdKey("mal", value.malId?.toLong())), value.names)
    is ImportedShikiAnime -> AnimeTitleSubject(value.title, listOf("shikimori:${value.id}"), value.names)
    else -> error("Unsupported anime title model: ${value.javaClass.simpleName}")
}
@Composable
fun animeTitle(value: Any): String = LocalAnimeTitles.current.display(animeTitleSubject(value))
@Composable
fun animeTitle(original: String, key: String): String = LocalAnimeTitles.current.display(AnimeTitleSubject(original, listOf(key)))
