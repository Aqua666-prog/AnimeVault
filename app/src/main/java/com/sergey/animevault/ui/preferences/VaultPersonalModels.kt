package com.sergey.animevault.ui.preferences

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** User-owned state: never conflated with provider ratings, watch progress, or favourites. */
data class VaultPersonalTitle(
    val rating: Int? = null,
    val note: String = "",
    val notifyEpisodes: Boolean = false,
    val translationKey: String? = null,
    val seenEpisodeIds: List<String> = emptyList(),
    val baselineReady: Boolean = false,
    val updatedAt: Long = 0L,
)

enum class GenreMatchMode(val label: String) {
    ANY("Любой из выбранных"), ALL("Все выбранные"),
}

data class CatalogAdvancedFilter(
    val genres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val genreMode: GenreMatchMode = GenreMatchMode.ANY,
    val season: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val episodesFrom: Int? = null,
    val episodesTo: Int? = null,
    val excludeMyLists: Boolean = false,
)

data class CatalogPreset(
    val name: String,
    val providerId: String,
    val query: String = "",
    val genre: String? = null,
    val collection: String = "ALL",
    val sort: String = "SOURCE",
    val year: Int? = null,
    val type: String? = null,
    val rating: Double? = null,
    val status: String = "ALL",
    val episodes: String = "ANY",
    val advanced: CatalogAdvancedFilter = CatalogAdvancedFilter(),
)

/** JSON fields are deliberately explicit and version-tolerant for backup import. */
object VaultPersonalCodec {
    private val gson = Gson()
    private val titlesType = object : TypeToken<Map<String, VaultPersonalTitle>>() {}.type
    private val presetsType = object : TypeToken<List<CatalogPreset>>() {}.type

    fun titlesToJson(value: Map<String, VaultPersonalTitle>): String = gson.toJson(value)
    fun presetsToJson(value: List<CatalogPreset>): String = gson.toJson(value)

    fun titlesFromJson(raw: String?): Map<String, VaultPersonalTitle> = runCatching {
        (gson.fromJson<Map<String, VaultPersonalTitle>>(raw ?: "{}", titlesType) ?: emptyMap())
            .filter { (key, item) -> key.isNotBlank() && item.rating?.let { it in 1..5 } != false }
    }.getOrDefault(emptyMap())

    fun presetsFromJson(raw: String?): List<CatalogPreset> = runCatching {
        (gson.fromJson<List<CatalogPreset>>(raw ?: "[]", presetsType) ?: emptyList())
            .filter { it.name.isNotBlank() && it.providerId.isNotBlank() }.take(30)
    }.getOrDefault(emptyList())
}
