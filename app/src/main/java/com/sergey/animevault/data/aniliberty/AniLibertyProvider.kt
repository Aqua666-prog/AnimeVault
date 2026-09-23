package com.sergey.animevault.data.aniliberty

import com.sergey.animevault.data.online.OnlineProvider
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineStreamType

class AniLibertyProvider(
    private val api: AniLibertyApi,
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANI_LIBERTY,
        name = "AniLiberty",
        description = "Официальный каталог и HLS-потоки AniLiberty",
    )

    override suspend fun getCatalog(
        page: Int,
        limit: Int,
        search: String,
    ): OnlineCatalogPage {
        val response = api.getCatalog(
            page = page,
            limit = limit,
            search = search.trim().takeIf(String::isNotEmpty),
        )
        val pagination = response.meta?.pagination
        return OnlineCatalogPage(
            releases = response.data.map(ReleaseDto::toCard),
            currentPage = pagination?.currentPage ?: page,
            totalPages = pagination?.totalPages ?: page,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails = api.getRelease(id).toDetails()
}

internal fun ReleaseDto.toCard(): OnlineReleaseCard = OnlineReleaseCard(
    providerId = OnlineProviderIds.ANI_LIBERTY,
    providerName = "AniLiberty",
    id = id.toString(),
    alias = alias.orEmpty(),
    name = displayName(),
    englishName = name?.english?.takeIf(String::isNotBlank),
    posterUrl = poster.cardUrl(),
    year = year,
    type = type?.description ?: type?.value,
    season = season?.description ?: season?.value,
    episodeCount = episodesTotal,
    isOngoing = isOngoing || isInProduction,
    genres = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) },
)

internal fun ReleaseDto.toDetails(): OnlineReleaseDetails = OnlineReleaseDetails(
    providerId = OnlineProviderIds.ANI_LIBERTY,
    providerName = "AniLiberty",
    id = id.toString(),
    alias = alias.orEmpty(),
    name = displayName(),
    englishName = name?.english?.takeIf(String::isNotBlank),
    posterUrl = poster.detailUrl(),
    year = year,
    type = type?.description ?: type?.value,
    season = season?.description ?: season?.value,
    episodeCount = episodesTotal,
    description = description?.takeIf(String::isNotBlank),
    notification = notification?.takeIf(String::isNotBlank),
    genres = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) },
    isOngoing = isOngoing || isInProduction,
    isBlocked = isBlockedByGeo || isBlockedByCopyrights,
    episodes = episodes
        .map(EpisodeDto::toModel)
        .sortedWith(
            compareBy<OnlineEpisode> { it.sortOrder ?: Double.MAX_VALUE }
                .thenBy { it.ordinal ?: Double.MAX_VALUE },
        ),
)

private fun ReleaseDto.displayName(): String = name?.main
    ?.takeIf(String::isNotBlank)
    ?: name?.english?.takeIf(String::isNotBlank)
    ?: alias?.takeIf(String::isNotBlank)
    ?: "Релиз #$id"

private fun EpisodeDto.toModel(): OnlineEpisode = OnlineEpisode(
    providerId = OnlineProviderIds.ANI_LIBERTY,
    id = id,
    releaseId = releaseId.toString(),
    ordinal = ordinal,
    name = name?.takeIf(String::isNotBlank),
    previewUrl = preview.cardUrl(),
    durationMs = (duration ?: 0L) * 1_000L,
    sortOrder = sortOrder,
    streams = listOfNotNull(
        hls1080.stream(1080),
        hls720.stream(720),
        hls480.stream(480),
    ),
)

private fun String?.stream(quality: Int): OnlineStream? = this
    ?.takeIf(String::isNotBlank)
    ?.let {
        OnlineStream(
            id = "${quality}p",
            quality = quality,
            url = it,
            type = OnlineStreamType.HLS,
        )
    }

private fun ImageDto?.cardUrl(): String? = this?.optimized?.thumbnail
    ?.takeIf(String::isNotBlank)
    ?.absoluteImageUrl()
    ?: this?.thumbnail?.takeIf(String::isNotBlank)?.absoluteImageUrl()
    ?: detailUrl()

private fun ImageDto?.detailUrl(): String? = this?.optimized?.preview
    ?.takeIf(String::isNotBlank)
    ?.absoluteImageUrl()
    ?: this?.optimized?.src?.takeIf(String::isNotBlank)?.absoluteImageUrl()
    ?: this?.preview?.takeIf(String::isNotBlank)?.absoluteImageUrl()
    ?: this?.src?.takeIf(String::isNotBlank)?.absoluteImageUrl()

internal fun String.absoluteImageUrl(): String = when {
    startsWith("https://") || startsWith("http://") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> "https://aniliberty.top$this"
    else -> "https://aniliberty.top/$this"
}
