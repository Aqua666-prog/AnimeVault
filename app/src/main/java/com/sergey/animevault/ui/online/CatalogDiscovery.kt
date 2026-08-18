package com.sergey.animevault.ui.online

import com.sergey.animevault.data.online.OnlineReleaseCard
import java.util.Calendar
import java.util.Locale

enum class CatalogSort(val title: String) {
    SOURCE("По порядку источника"),
    GENRE("По жанрам"),
    TITLE("По алфавиту"),
    NEWEST("Сначала новые"),
    OLDEST("Сначала старые"),
}

enum class CatalogStatusFilter(val title: String) {
    ALL("Любой статус"),
    ONGOING("Онгоинги"),
    FINISHED("Завершённые"),
}

enum class CatalogEpisodeFilter(val title: String) {
    ANY("Любое число серий"),
    SHORT("1–13 серий"),
    STANDARD("14–26 серий"),
    LONG("27+ серий"),
}

enum class CatalogLayout(val title: String) {
    GRID("Сетка"),
    LIST("Список"),
}

enum class ThematicCollection(
    val title: String,
    val subtitle: String,
    private val genreMarkers: Set<String> = emptySet(),
) {
    ALL("Весь каталог", "Все загруженные релизы"),
    ONGOING("Сейчас выходит", "Новые серии продолжают выходить"),
    NEW_RELEASES("Новинки", "Релизы последних двух лет"),
    FANTASY("Фэнтези и иные миры", "Магия, приключения и исэкай", setOf(
        "фэнтези", "fantasy", "исэкай", "isekai", "магия", "magic",
    )),
    ROMANCE("Романтика", "Любовные истории и повседневность", setOf(
        "романтика", "romance", "седзе", "shoujo", "shojo",
    )),
    MYSTERY("Мистика и хоррор", "Тайны, сверхъестественное и ужасы", setOf(
        "мистика", "сверхъестественное", "ужасы", "триллер", "детектив",
        "mystery", "supernatural", "horror", "thriller", "detective",
    )),
    HISTORICAL("История и самураи", "Исторические эпохи, войны и традиции", setOf(
        "исторический", "история", "самураи", "военное", "historical", "history", "samurai", "military",
    )),
    COMEDY("Комедии", "Лёгкие и смешные истории", setOf("комедия", "comedy")),
    ACTION("Экшен", "Битвы, приключения и суперспособности", setOf(
        "экшен", "боевик", "приключения", "супер сила", "action", "adventure", "super power",
    )),
    SHORT("Короткие истории", "До 13 серий — удобно посмотреть за несколько вечеров"),
    ;

    fun matches(release: OnlineReleaseCard, currentYear: Int): Boolean = when (this) {
        ALL -> true
        ONGOING -> release.isOngoing
        NEW_RELEASES -> release.year?.let { it >= currentYear - 1 } == true
        SHORT -> release.episodeCount?.let { it in 1..13 } == true
        else -> release.genres.any { genre ->
            val normalizedGenre = genre.discoveryKey()
            genreMarkers.any { marker ->
                normalizedGenre == marker || normalizedGenre.contains(marker)
            }
        }
    }
}

data class CollectionOption(
    val collection: ThematicCollection,
    val count: Int,
)

internal fun availableCatalogGenres(releases: List<OnlineReleaseCard>): List<String> = releases
    .asSequence()
    .flatMap { it.genres.asSequence() }
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy(String::discoveryKey)
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    .toList()

internal fun availableCatalogYears(releases: List<OnlineReleaseCard>): List<Int> = releases
    .mapNotNull(OnlineReleaseCard::year)
    .distinct()
    .sortedDescending()

internal fun availableCatalogTypes(releases: List<OnlineReleaseCard>): List<String> = releases
    .mapNotNull(OnlineReleaseCard::type)
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy(String::discoveryKey)
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

internal fun availableCollections(
    releases: List<OnlineReleaseCard>,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
): List<CollectionOption> = ThematicCollection.entries
    .map { collection ->
        CollectionOption(collection, releases.count { collection.matches(it, currentYear) })
    }
    .filter { it.collection == ThematicCollection.ALL || it.count > 0 }

internal fun discoverCatalog(
    releases: List<OnlineReleaseCard>,
    selectedGenre: String?,
    collection: ThematicCollection,
    sort: CatalogSort,
    selectedYear: Int? = null,
    selectedType: String? = null,
    status: CatalogStatusFilter = CatalogStatusFilter.ALL,
    episodes: CatalogEpisodeFilter = CatalogEpisodeFilter.ANY,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
): List<OnlineReleaseCard> {
    val genreKey = selectedGenre?.discoveryKey()?.takeIf(String::isNotBlank)
    val typeKey = selectedType?.discoveryKey()?.takeIf(String::isNotBlank)
    val filtered = releases.filter { release ->
        collection.matches(release, currentYear) &&
            (genreKey == null || release.genres.any { it.discoveryKey() == genreKey }) &&
            (selectedYear == null || release.year == selectedYear) &&
            (typeKey == null || release.type?.discoveryKey() == typeKey) &&
            when (status) {
                CatalogStatusFilter.ALL -> true
                CatalogStatusFilter.ONGOING -> release.isOngoing
                CatalogStatusFilter.FINISHED -> !release.isOngoing
            } &&
            when (episodes) {
                CatalogEpisodeFilter.ANY -> true
                CatalogEpisodeFilter.SHORT -> release.episodeCount?.let { it in 1..13 } == true
                CatalogEpisodeFilter.STANDARD -> release.episodeCount?.let { it in 14..26 } == true
                CatalogEpisodeFilter.LONG -> release.episodeCount?.let { it >= 27 } == true
            }
    }
    return when (sort) {
        CatalogSort.SOURCE -> filtered
        CatalogSort.GENRE -> filtered.sortedWith(
            compareBy<OnlineReleaseCard> { release ->
                release.genres.minByOrNull { it.discoveryKey() }?.discoveryKey().orEmpty()
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        CatalogSort.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        CatalogSort.NEWEST -> filtered.sortedWith(
            compareByDescending<OnlineReleaseCard> { it.year ?: Int.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        CatalogSort.OLDEST -> filtered.sortedWith(
            compareBy<OnlineReleaseCard> { it.year ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
}

private fun String.discoveryKey(): String = trim()
    .lowercase(Locale.ROOT)
    .replace('ё', 'е')
