package com.sergey.animevault.data.online

import com.sergey.animevault.util.runCatchingCancellable
import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Virtual provider that joins several independent providers into one catalogue.
 *
 * It never fetches video on its own. A unified release ID contains only the
 * provider/release references required to reconstruct the title after process
 * recreation. Playback is delegated back to the original provider.
 */
class UnifiedOnlineProvider(
    providers: List<OnlineProvider>,
) : OnlineProvider {
    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.UNIFIED,
        name = "Все источники",
        description = "Единый каталог: объединяет совпадающие тайтлы и собирает озвучки из подключённых источников",
        isExperimental = true,
        searchHint = "Искать сразу во всех источниках",
    )

    private val sourceProviders = providers
        .filterNot { it.descriptor.id == OnlineProviderIds.UNIFIED }
        .associateBy { it.descriptor.id }

    // Jut.su intentionally stays outside the global search because its current
    // adapter accepts a URL/slug rather than a free-text anime title.
    private val catalogProviders = sourceProviders.values
        .filterNot { it.descriptor.id == OnlineProviderIds.JUT_SU }
    private val catalogCache = ConcurrentHashMap<CatalogCacheKey, CachedCatalog>()

    init {
        require(sourceProviders.isNotEmpty()) { "UnifiedOnlineProvider needs at least one source provider" }
    }

    internal fun clearCatalogCache() {
        catalogCache.clear()
    }

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceAtLeast(1)
        val perProviderLimit = ((safeLimit + catalogProviders.size - 1) / catalogProviders.size)
            .coerceAtLeast(MIN_PER_PROVIDER_PAGE_SIZE)
            .coerceAtMost(MAX_PER_PROVIDER_PAGE_SIZE)

        val results = supervisorScope {
            catalogProviders.map { provider ->
                async {
                    provider to runCatchingCancellable {
                        cachedCatalogPage(
                            provider = provider,
                            page = safePage,
                            limit = perProviderLimit,
                            search = search,
                        )
                    }
                }
            }.awaitAll()
        }

        val successful = results.mapNotNull { (provider, result) ->
            result.getOrNull()?.let { provider to it }
        }
        if (successful.isEmpty()) {
            val firstError = results.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull() }
            throw OnlineSourceException(
                "Ни один источник единого каталога не ответил",
                firstError,
            )
        }

        val cards = successful.flatMap { (_, catalog) -> catalog.releases }
        val merged = mergeCatalogCards(cards)
        return OnlineCatalogPage(
            releases = merged,
            currentPage = safePage,
            totalPages = successful.maxOfOrNull { (_, catalog) -> catalog.totalPages }
                ?.coerceAtLeast(safePage)
                ?: safePage,
        )
    }

    private suspend fun cachedCatalogPage(
        provider: OnlineProvider,
        page: Int,
        limit: Int,
        search: String,
    ): OnlineCatalogPage {
        val now = monotonicNowMs()
        val key = CatalogCacheKey(
            providerId = provider.descriptor.id,
            page = page,
            limit = limit,
            search = search.trim().lowercase(Locale.ROOT),
        )
        catalogCache[key]?.takeIf { now - it.storedAtMs <= CATALOG_CACHE_TTL_MS }?.let {
            return it.page
        }
        val loaded = provider.getCatalog(page = page, limit = limit, search = search)
        catalogCache[key] = CachedCatalog(page = loaded, storedAtMs = now)
        if (catalogCache.size > MAX_CATALOG_CACHE_ENTRIES) {
            catalogCache.entries.removeIf { (_, cached) ->
                now - cached.storedAtMs > CATALOG_CACHE_TTL_MS
            }
            if (catalogCache.size > MAX_CATALOG_CACHE_ENTRIES) {
                catalogCache.entries
                    .sortedBy { it.value.storedAtMs }
                    .take(catalogCache.size - MAX_CATALOG_CACHE_ENTRIES)
                    .forEach { entry -> catalogCache.remove(entry.key, entry.value) }
            }
        }
        return loaded
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails {
        val references = UnifiedReleaseReference.decode(id)
            .members
            .filter { sourceProviders.containsKey(it.providerId) }
        if (references.isEmpty()) {
            throw OnlineSourceException("Единый каталог: ссылки на исходные релизы устарели")
        }

        val loaded = supervisorScope {
            references.map { member ->
                async {
                    val provider = sourceProviders.getValue(member.providerId)
                    provider to runCatchingCancellable { provider.getRelease(member.releaseId) }
                }
            }.awaitAll()
        }

        val successful = loaded.mapNotNull { (provider, result) ->
            result.getOrNull()?.let { SourcedDetails(provider, it) }
        }
        if (successful.isEmpty()) {
            val firstError = loaded.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull() }
            val providerErrors = loaded.mapNotNull { (provider, result) ->
                result.exceptionOrNull()?.let { error ->
                    val reason = generateSequence(error) { it.cause }
                        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
                        .firstOrNull()
                        ?: "неизвестная ошибка"
                    "${provider.descriptor.name}: $reason"
                }
            }.take(MAX_VISIBLE_PROVIDER_ERRORS)
            throw OnlineSourceException(
                buildString {
                    append("Не удалось открыть тайтл")
                    if (providerErrors.isNotEmpty()) {
                        append(". ")
                        append(providerErrors.joinToString("; "))
                    }
                },
                firstError,
            )
        }
        return mergeReleaseDetails(id, successful)
    }

    override suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> {
        if (episode.sources.isEmpty()) return episode.streams

        val results = supervisorScope {
            episode.sources.mapNotNull { source ->
                val provider = sourceProviders[source.providerId] ?: return@mapNotNull null
                async {
                    val resolved = runCatchingCancellable {
                        provider.resolveStreams(source.releaseId, source.toEpisode())
                    }.getOrElse {
                        // Direct streams remain useful even if a provider-specific
                        // resolver temporarily fails. Empty lazy sources simply let
                        // the other providers continue.
                        source.streams
                    }
                    resolved.map { stream -> stream.withProviderLabel(provider.descriptor.name) }
                }
            }.awaitAll()
        }.flatten()
            .distinctBy { stream ->
                listOf(
                    stream.url,
                    stream.quality?.toString().orEmpty(),
                    stream.translation.orEmpty(),
                    stream.sourceName.orEmpty(),
                ).joinToString("\u001F")
            }
            .sortedWith(
                compareBy<OnlineStream> { it.translation.orEmpty().lowercase(Locale.ROOT) }
                    .thenByDescending { it.quality ?: 0 }
                    .thenBy { it.sourceName.orEmpty() },
            )

        return results.ifEmpty { episode.streams }
    }

    private fun mergeCatalogCards(cards: List<OnlineReleaseCard>): List<OnlineReleaseCard> {
        val groups = mutableListOf<MutableList<OnlineReleaseCard>>()
        cards.forEach { card ->
            val bestGroup = groups
                .map { group -> group to group.maxOf { existing -> OnlineTitleMatcher.score(existing, card) } }
                .maxByOrNull { (_, score) -> score }
                ?.takeIf { (_, score) -> score >= MATCH_THRESHOLD }
                ?.first

            if (bestGroup == null) groups += mutableListOf(card) else bestGroup += card
        }

        return groups.map(::mergeCardGroup)
            .sortedWith(
                compareByDescending<OnlineReleaseCard> { it.isOngoing }
                    .thenByDescending { it.year ?: 0 }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
    }

    private fun mergeCardGroup(group: List<OnlineReleaseCard>): OnlineReleaseCard {
        val best = group.maxBy(::cardRichness)
        val members = group
            .map { UnifiedReleaseMember(it.providerId, it.id) }
            .distinct()
        val sourceNames = group.map(OnlineReleaseCard::providerName).distinct()
        return best.copy(
            providerId = OnlineProviderIds.UNIFIED,
            providerName = sourceNames.joinToString(" + "),
            id = UnifiedReleaseReference(members).encode(),
            posterUrl = group.firstNotNullOfOrNull(OnlineReleaseCard::posterUrl) ?: best.posterUrl,
            year = best.year ?: group.firstNotNullOfOrNull(OnlineReleaseCard::year),
            type = best.type ?: group.firstNotNullOfOrNull(OnlineReleaseCard::type),
            season = best.season ?: group.firstNotNullOfOrNull(OnlineReleaseCard::season),
            episodeCount = group.mapNotNull(OnlineReleaseCard::episodeCount).maxOrNull(),
            isOngoing = group.any(OnlineReleaseCard::isOngoing),
            genres = group.flatMap(OnlineReleaseCard::genres).distinctBy { it.lowercase(Locale.ROOT) },
            externalIds = mergeExternalIds(group.map(OnlineReleaseCard::externalIds)),
        )
    }

    private fun mergeReleaseDetails(
        unifiedId: String,
        sourced: List<SourcedDetails>,
    ): OnlineReleaseDetails {
        val best = sourced.maxBy { detailRichness(it.details) }.details
        val episodes = mergeEpisodes(unifiedId, sourced)
        val sourceNames = sourced.map { it.provider.descriptor.name }.distinct()
        val descriptions = sourced.mapNotNull { it.details.description?.trim()?.takeIf(String::isNotBlank) }

        return best.copy(
            providerId = OnlineProviderIds.UNIFIED,
            providerName = descriptor.name,
            id = unifiedId,
            posterUrl = sourced.firstNotNullOfOrNull { it.details.posterUrl } ?: best.posterUrl,
            year = best.year ?: sourced.firstNotNullOfOrNull { it.details.year },
            type = best.type ?: sourced.firstNotNullOfOrNull { it.details.type },
            season = best.season ?: sourced.firstNotNullOfOrNull { it.details.season },
            episodeCount = episodes.size,
            description = descriptions.maxByOrNull(String::length) ?: best.description,
            notification = "Источники: ${sourceNames.joinToString(", ")}",
            genres = sourced.flatMap { it.details.genres }.distinctBy { it.lowercase(Locale.ROOT) },
            isOngoing = sourced.any { it.details.isOngoing },
            isBlocked = sourced.all { it.details.isBlocked },
            episodes = episodes,
            externalIds = mergeExternalIds(sourced.map { it.details.externalIds }),
        )
    }

    private fun mergeEpisodes(
        unifiedReleaseId: String,
        sourced: List<SourcedDetails>,
    ): List<OnlineEpisode> {
        val groups = linkedMapOf<EpisodeMergeKey, MutableList<SourcedEpisode>>()
        sourced.forEach { sourceDetail ->
            sourceDetail.details.episodes.forEach { episode ->
                val key = episode.mergeKey()
                groups.getOrPut(key) { mutableListOf() } += SourcedEpisode(
                    provider = sourceDetail.provider,
                    releaseId = sourceDetail.details.id,
                    episode = episode,
                )
            }
        }

        return groups.map { (key, group) ->
            val sourceEpisodes = group.map { sourcedEpisode ->
                val episode = sourcedEpisode.episode
                OnlineEpisodeSource(
                    providerId = sourcedEpisode.provider.descriptor.id,
                    releaseId = episode.releaseId.ifBlank { sourcedEpisode.releaseId },
                    episodeId = episode.id,
                    ordinal = episode.ordinal,
                    name = episode.name,
                    previewUrl = episode.previewUrl,
                    durationMs = episode.durationMs,
                    sortOrder = episode.sortOrder,
                    streams = episode.streams,
                    sourceRef = episode.sourceRef,
                )
            }
            val rawEpisodes = group.map(SourcedEpisode::episode)
            val ordinal = rawEpisodes.mapNotNull(OnlineEpisode::ordinal).firstOrNull()
            val sortOrder = mergedSortOrder(key, rawEpisodes)
            val mergedStreams = group.flatMap { sourcedEpisode ->
                sourcedEpisode.episode.streams.map { stream ->
                    stream.withProviderLabel(sourcedEpisode.provider.descriptor.name)
                }
            }.distinctBy { stream ->
                listOf(stream.url, stream.translation.orEmpty(), stream.sourceName.orEmpty()).joinToString("\u001F")
            }

            OnlineEpisode(
                providerId = OnlineProviderIds.UNIFIED,
                id = unifiedEpisodeId(unifiedReleaseId, key),
                releaseId = unifiedReleaseId,
                ordinal = ordinal,
                name = bestEpisodeName(rawEpisodes, ordinal),
                previewUrl = rawEpisodes.firstNotNullOfOrNull(OnlineEpisode::previewUrl),
                durationMs = rawEpisodes.maxOfOrNull(OnlineEpisode::durationMs) ?: 0L,
                sortOrder = sortOrder,
                streams = mergedStreams,
                sourceRef = null,
                sources = sourceEpisodes,
            )
        }.sortedWith(
            compareBy<OnlineEpisode> { it.sortOrder ?: Double.MAX_VALUE }
                .thenBy { it.ordinal ?: Double.MAX_VALUE }
                .thenBy(OnlineEpisode::id),
        )
    }

    private companion object {
        const val MATCH_THRESHOLD = 72
        const val MIN_PER_PROVIDER_PAGE_SIZE = 8
        const val MAX_PER_PROVIDER_PAGE_SIZE = 24
        const val CATALOG_CACHE_TTL_MS = 120_000L
        const val MAX_CATALOG_CACHE_ENTRIES = 128
        const val MAX_VISIBLE_PROVIDER_ERRORS = 3
    }
}

private data class CatalogCacheKey(
    val providerId: String,
    val page: Int,
    val limit: Int,
    val search: String,
)

private data class CachedCatalog(
    val page: OnlineCatalogPage,
    val storedAtMs: Long,
)

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

internal data class UnifiedReleaseMember(
    val providerId: String,
    val releaseId: String,
)

internal data class UnifiedReleaseReference(
    val members: List<UnifiedReleaseMember>,
) {
    fun encode(): String {
        val charset = StandardCharsets.UTF_8.name()
        val payload = members.joinToString(MEMBER_SEPARATOR) { member ->
            URLEncoder.encode(member.providerId, charset) + FIELD_SEPARATOR +
                URLEncoder.encode(member.releaseId.replace("\n", ""), charset)
        }
        return PREFIX + payload
    }

    companion object {
        private const val PREFIX = "u:"
        private const val MEMBER_SEPARATOR = "|"
        private const val FIELD_SEPARATOR = "~"

        fun decode(value: String): UnifiedReleaseReference {
            if (!value.startsWith(PREFIX)) {
                throw OnlineSourceException("Единый каталог: некорректный идентификатор")
            }
            val charset = StandardCharsets.UTF_8.name()
            val members = value.removePrefix(PREFIX)
                .split(MEMBER_SEPARATOR)
                .mapNotNull { encoded ->
                    val separator = encoded.indexOf(FIELD_SEPARATOR)
                    if (separator <= 0 || separator >= encoded.lastIndex) return@mapNotNull null
                    runCatching {
                        UnifiedReleaseMember(
                            providerId = URLDecoder.decode(encoded.substring(0, separator), charset),
                            releaseId = URLDecoder.decode(encoded.substring(separator + 1), charset),
                        )
                    }.getOrNull()
                }
                .distinct()
            if (members.isEmpty()) throw OnlineSourceException("Единый каталог: пустой идентификатор")
            return UnifiedReleaseReference(members)
        }
    }
}

private data class SourcedDetails(
    val provider: OnlineProvider,
    val details: OnlineReleaseDetails,
)

private data class SourcedEpisode(
    val provider: OnlineProvider,
    val releaseId: String,
    val episode: OnlineEpisode,
)

private data class EpisodeMergeKey(
    val season: Int,
    val token: String,
    val numericOrdinal: Double?,
)

private fun OnlineEpisode.mergeKey(): EpisodeMergeKey {
    val season = when {
        sortOrder != null && sortOrder >= 10_000.0 -> floor(sortOrder / 10_000.0).toInt().coerceAtLeast(1)
        else -> EPISODE_SEASON_REGEX.find(name.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }
    val number = ordinal
    val token = number?.let(::canonicalDouble)
        ?: OnlineTitleMatcher.normalize(name.orEmpty()).takeIf(String::isNotBlank)
        ?: id
    return EpisodeMergeKey(season = season, token = token, numericOrdinal = number)
}

private fun mergedSortOrder(key: EpisodeMergeKey, episodes: List<OnlineEpisode>): Double? {
    val ordinal = key.numericOrdinal
    if (ordinal != null) {
        val hasEncodedSeason = key.season > 1 || episodes.any { (it.sortOrder ?: 0.0) >= 10_000.0 }
        return if (hasEncodedSeason) key.season * 10_000.0 + ordinal else ordinal
    }
    return episodes.mapNotNull(OnlineEpisode::sortOrder).minOrNull()
}

private fun bestEpisodeName(episodes: List<OnlineEpisode>, ordinal: Double?): String? = episodes
    .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
    .maxByOrNull(String::length)
    ?: ordinal?.let { number -> "${canonicalDouble(number)} серия" }

private fun OnlineStream.withProviderLabel(providerName: String): OnlineStream {
    val existing = sourceName?.trim()?.takeIf(String::isNotBlank)
    val label = listOfNotNull(existing, providerName.takeIf { it != existing })
        .distinct()
        .joinToString(" · ")
    return copy(sourceName = label.ifBlank { null })
}

private fun mergeExternalIds(ids: List<ExternalAnimeIds>): ExternalAnimeIds = ExternalAnimeIds(
    shikimoriId = ids.firstNotNullOfOrNull(ExternalAnimeIds::shikimoriId),
    malId = ids.firstNotNullOfOrNull(ExternalAnimeIds::malId),
    anilistId = ids.firstNotNullOfOrNull(ExternalAnimeIds::anilistId),
)

private fun cardRichness(card: OnlineReleaseCard): Int =
    (if (!card.posterUrl.isNullOrBlank()) 5 else 0) +
        (if (!card.englishName.isNullOrBlank()) 2 else 0) +
        (if (card.year != null) 2 else 0) +
        (if (card.episodeCount != null) 1 else 0) +
        card.genres.size.coerceAtMost(4) +
        (if (card.externalIds.hasAny) 4 else 0)

private fun detailRichness(details: OnlineReleaseDetails): Int =
    (if (!details.posterUrl.isNullOrBlank()) 4 else 0) +
        (if (!details.description.isNullOrBlank()) 5 else 0) +
        (if (!details.englishName.isNullOrBlank()) 2 else 0) +
        details.genres.size.coerceAtMost(5) +
        details.episodes.size.coerceAtMost(8) +
        (if (details.externalIds.hasAny) 4 else 0)

private fun canonicalDouble(value: Double): String = BigDecimal.valueOf(value)
    .stripTrailingZeros()
    .toPlainString()

private fun unifiedEpisodeId(releaseId: String, key: EpisodeMergeKey): String =
    "ue:${shortHash(releaseId)}:${key.season}:${shortHash(key.token)}"

private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(10)
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val EPISODE_SEASON_REGEX = Regex("(?:season|сезон)\\s*(\\d+)", RegexOption.IGNORE_CASE)
