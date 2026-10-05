package com.sergey.animevault.data.animetka

import com.sergey.animevault.data.online.OnlineCatalogPage
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineProviderDescriptor
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineSourceException
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.ProviderCapabilities
import com.sergey.animevault.data.online.ProviderSearchMode
import com.sergey.animevault.data.online.TranslationAwareOnlineProvider
import com.sergey.animevault.data.online.decodeProviderTranslationKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class AnimetkaProvider internal constructor(
    private val api: AnimetkaApi = createAnimetkaApi(),
) : TranslationAwareOnlineProvider {
    constructor(client: OkHttpClient) : this(createAnimetkaApi(client))

    override val descriptor = OnlineProviderDescriptor(
        id = OnlineProviderIds.ANIMETKA,
        name = ANIMETKA_PROVIDER_NAME,
        description = "Каталог Аниметки, переводы, прямые HLS/MP4 и таймкоды заставок",
        searchHint = "Найти на Аниметке",
        healthProbeQuery = "Frieren",
        minimumSearchLength = 1,
        capabilities = ProviderCapabilities(
            catalog = true,
            search = true,
            releaseDetails = true,
            episodes = true,
            streams = true,
            translations = true,
            subtitles = true,
            directPlayback = true,
            downloads = ANIMETKA_DOWNLOADS_ENABLED,
            searchMode = ProviderSearchMode.TEXT,
        ),
    )

    private val detailsCache = ConcurrentHashMap<String, AnimetkaAnimeDto>()
    private val translationCache = ConcurrentHashMap<String, List<AnimetkaTranslationDto>>()

    override suspend fun getCatalog(page: Int, limit: Int, search: String): OnlineCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val envelope = apiCall("каталог") {
            if (search.isBlank()) {
                if (safePage > 1) return@apiCall AnimetkaCatalogEnvelope(emptyList(), null)
                AnimetkaJson.parseCatalog(api.main())
            } else {
                val offset = (safePage - 1) * safeLimit
                AnimetkaJson.parseCatalog(api.search(search.trim(), safeLimit, offset))
            }
        }
        envelope.items.forEach { detailsCache[it.id] = it }
        val totalPages = envelope.total
            ?.let { total -> ceil(total.toDouble() / safeLimit).toInt().coerceAtLeast(safePage) }
            ?: safePage
        return OnlineCatalogPage(
            releases = envelope.items.map(AnimetkaAnimeDto::toOnlineCard),
            currentPage = safePage,
            totalPages = totalPages,
        )
    }

    override suspend fun getRelease(id: String): OnlineReleaseDetails = coroutineScope {
        validateReleaseId(id)
        val detailsDeferred = async { loadDetails(id) }
        val defaultPlaylistDeferred = async {
            apiCall("список переводов") { AnimetkaJson.parsePlaylist(api.playlist(id)) }
        }
        val details = detailsDeferred.await()
        val defaultPlaylist = defaultPlaylistDeferred.await()
        translationCache[id] = defaultPlaylist.translations
        val selected = chooseDefaultTranslation(defaultPlaylist)
        val explicitPlaylist = selected?.let { translation ->
            apiCall("плейлист перевода ${translation.name}") {
                AnimetkaJson.parsePlaylist(api.playlist(id, translation.id))
            }
        } ?: defaultPlaylist
        details.toOnlineDetails(
            playlist = explicitPlaylist,
            selectedTranslation = selected,
            translations = defaultPlaylist.translations,
        )
    }

    override suspend fun getReleaseForTranslation(
        releaseId: String,
        translationKey: String,
    ): OnlineReleaseDetails {
        validateReleaseId(releaseId)
        val selection = decodeProviderTranslationKey(translationKey)
            ?: throw OnlineSourceException("Аниметка: некорректный ключ перевода")
        if (selection.providerId != OnlineProviderIds.ANIMETKA || selection.releaseId != releaseId) {
            throw OnlineSourceException("Аниметка: перевод относится к другому релизу")
        }
        val translations = translationCache[releaseId].orEmpty().ifEmpty {
            val initial = apiCall("список переводов") { AnimetkaJson.parsePlaylist(api.playlist(releaseId)) }
            translationCache[releaseId] = initial.translations
            initial.translations
        }
        val selected = translations.firstOrNull { it.id == selection.translationId }
            ?: throw OnlineSourceException("Аниметка: выбранный перевод больше недоступен")
        val playlist = apiCall("плейлист перевода ${selected.name}") {
            AnimetkaJson.parsePlaylist(api.playlist(releaseId, selected.id))
        }
        return loadDetails(releaseId).toOnlineDetails(
            playlist = playlist,
            selectedTranslation = selected,
            translations = translations,
        )
    }

    override suspend fun resolveStreams(releaseId: String, episode: OnlineEpisode): List<OnlineStream> {
        val ref = decodeAnimetkaEpisodeRef(episode.sourceRef) ?: return episode.streams
        val (translationId, rawEpisodeId) = ref
        val translations = translationCache[releaseId].orEmpty().ifEmpty {
            val initial = apiCall("список переводов") { AnimetkaJson.parsePlaylist(api.playlist(releaseId)) }
            translationCache[releaseId] = initial.translations
            initial.translations
        }
        val translation = translations.firstOrNull { it.id == translationId }
            ?: throw OnlineSourceException("Аниметка: выбранный перевод больше недоступен")
        val playlist = apiCall("обновление потока") {
            AnimetkaJson.parsePlaylist(api.playlist(releaseId, translationId))
        }
        val fresh = playlist.episodes.firstOrNull { it.id == rawEpisodeId }
            ?: episode.ordinal?.let { ordinal ->
                playlist.episodes.firstOrNull { candidate ->
                    val number = Regex("\\d+(?:[.,]\\d+)?").find(candidate.id)?.value?.replace(',', '.')?.toDoubleOrNull()
                    number != null && kotlin.math.abs(number - ordinal) < 0.0001
                }
            }
            ?: return emptyList()
        val key = com.sergey.animevault.data.online.providerTranslationKey(
            OnlineProviderIds.ANIMETKA,
            releaseId,
            translationId,
        )
        val skipData = fresh.skipData.toOnlineSkipData()
        return parseAnimetkaPlayerJsStreams(
            raw = fresh.file,
            releaseId = releaseId,
            episodeId = fresh.id,
            translationId = translationId,
            translationName = translation.name,
            translationKey = key,
        ).map { stream -> stream.copy(skipData = skipData) }
    }

    private suspend fun loadDetails(id: String): AnimetkaAnimeDto = detailsCache[id] ?: apiCall("карточка") {
        AnimetkaJson.parseAnime(api.details(id))
            ?: throw OnlineSourceException("Аниметка: сервер вернул карточку неизвестного формата")
    }.also { detailsCache[id] = it }

    private fun chooseDefaultTranslation(playlist: AnimetkaPlaylistDto): AnimetkaTranslationDto? {
        val selectedId = playlist.selectedTranslationId
        return playlist.translations.firstOrNull { it.id == selectedId }
            ?: playlist.translations.firstOrNull()
    }

    private fun validateReleaseId(value: String) {
        if (value.isBlank() || value.length > 64 || !value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw OnlineSourceException("Аниметка: некорректный ID релиза")
        }
    }

    private suspend fun <T> apiCall(stage: String, block: suspend () -> T): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: HttpException) {
        throw OnlineSourceException(
            when (error.code()) {
                404 -> "Аниметка: материал не найден"
                429 -> error.response()?.headers()?.get("Retry-After")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { retryAfter -> "Аниметка: слишком много запросов, повторите через $retryAfter" }
                    ?: "Аниметка: слишком много запросов, повторите позже"
                in 500..599 -> "Аниметка: сервер временно недоступен"
                else -> "Аниметка: ошибка $stage (HTTP ${error.code()})"
            },
            error,
        )
    } catch (error: IOException) {
        throw OnlineSourceException("Аниметка: сеть недоступна при запросе $stage", error)
    } catch (error: OnlineSourceException) {
        throw error
    } catch (error: Throwable) {
        throw OnlineSourceException("Аниметка: не удалось разобрать $stage", error)
    }
}

internal const val ANIMETKA_PROVIDER_NAME = "Аниметка"

/** Включить только после ручного AC-09 на устройстве: полный файл, авиарежим, seek + resume. */
internal const val ANIMETKA_DOWNLOADS_ENABLED = false
