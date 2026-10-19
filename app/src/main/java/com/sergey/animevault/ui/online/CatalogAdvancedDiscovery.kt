package com.sergey.animevault.ui.online

import com.sergey.animevault.data.online.OnlineReleaseCard
import com.sergey.animevault.ui.preferences.CatalogAdvancedFilter
import com.sergey.animevault.ui.preferences.GenreMatchMode
import java.util.Locale

/** Client-side filtering only: does not claim to filter unseen server pages. */
internal fun advancedCatalogFilter(
    releases: List<OnlineReleaseCard>,
    filter: CatalogAdvancedFilter,
    listedTitleKeys: Set<String>,
): List<OnlineReleaseCard> {
    val included = filter.genres.map { it.trim().lowercase(Locale.ROOT).replace('ё', 'е') }.toSet()
    val excluded = filter.excludedGenres.map { it.trim().lowercase(Locale.ROOT).replace('ё', 'е') }.toSet()
    return releases.filter { release ->
        val available = release.genres.map { it.trim().lowercase(Locale.ROOT).replace('ё', 'е') }.toSet()
        (included.isEmpty() || when (filter.genreMode) {
            GenreMatchMode.ANY -> included.any { it in available }
            GenreMatchMode.ALL -> included.all { it in available }
        }) && excluded.none { it in available } &&
            (filter.season.isNullOrBlank() || release.season?.equals(filter.season, ignoreCase = true) == true) &&
            (filter.yearFrom == null || release.year?.let { it >= filter.yearFrom } == true) &&
            (filter.yearTo == null || release.year?.let { it <= filter.yearTo } == true) &&
            (filter.episodesFrom == null || release.episodeCount?.let { it >= filter.episodesFrom } == true) &&
            (filter.episodesTo == null || release.episodeCount?.let { it <= filter.episodesTo } == true) &&
            (!filter.excludeMyLists || "online:${release.providerId}:${release.id}" !in listedTitleKeys)
    }
}

internal fun CatalogAdvancedFilter.isActive(): Boolean = genres.isNotEmpty() || excludedGenres.isNotEmpty() ||
    !season.isNullOrBlank() || yearFrom != null || yearTo != null || episodesFrom != null || episodesTo != null || excludeMyLists
