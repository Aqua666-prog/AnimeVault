package com.sergey.animevault.data.metadata

import com.google.gson.JsonParseException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException

/** Only allowlisted enums and numbers leave the loader: no URLs, queries, bodies or exception messages. */
enum class CharacterSource(val label: String) {
    SHIKIMORI("Shikimori"), ANIMETKA("Аниметка"), TENRAI("Tenrai"), SHIKIMORI_IO("Постер shikimori.io"),
}

enum class CharacterStage(val label: String) {
    LOAD("загрузка"), GRAPHQL("GraphQL"), SEARCH("поиск"), DETAILS("карточка"),
    MAPPING("сопоставление ID"), CHARACTERS("персонажи"), POSTER("изображение"),
}

enum class CharacterLoadState { STARTED, SUCCESS, EMPTY, SKIPPED, ERROR }

enum class CharacterLoadReason(val label: String) {
    OK("успешно"), HTTP_ERROR("ошибка HTTP"), TIMEOUT("тайм-аут"), DNS("ошибка DNS"),
    TLS("ошибка TLS"), NETWORK("сетевая ошибка"), INVALID_RESPONSE("неверный формат ответа"),
    IMAGE_LOAD("ошибка загрузки или декодирования изображения"),
    GRAPHQL_ERROR("ошибка GraphQL"), NO_MATCH("нет подтверждённого соответствия ID"),
    MISSING_IDS("нет внешних ID для проверки"), DISABLED("источник отключён"),
    NOT_CONFIGURED("источник не настроен"), NO_MAL_ID("нет MAL ID"),
    CACHE_HIT("проверенное соответствие из кеша"), UNKNOWN("непредвиденная ошибка"),
}

data class CharacterLoadDiagnostic(
    val source: CharacterSource,
    val stage: CharacterStage,
    val state: CharacterLoadState,
    val reason: CharacterLoadReason = CharacterLoadReason.OK,
    val httpCode: Int? = null,
    val itemCount: Int? = null,
    val shikimoriAnimeId: Long? = null,
    val animetkaAnimeId: Long? = null,
    val malAnimeId: Long? = null,
    val anilistAnimeId: Long? = null,
) {
    fun summary(): String = "${source.label} (${stage.label}): " +
        (httpCode?.let { "HTTP $it — " } ?: "") + reason.label

    fun logLine(): String = "source=${source.name} stage=${stage.name} state=${state.name} " +
        "http=${httpCode ?: "none"} reason=${reason.name} count=${itemCount ?: "none"} " +
        "anime_shikimori=${shikimoriAnimeId ?: "none"} anime_animetka=${animetkaAnimeId ?: "none"} " +
        "anime_mal=${malAnimeId ?: "none"} anime_anilist=${anilistAnimeId ?: "none"}"
}

internal class CharacterSourceFailure(
    val diagnostic: CharacterLoadDiagnostic,
    cause: Throwable? = null,
) : IOException(diagnostic.summary(), cause)

internal fun characterFailure(
    source: CharacterSource,
    stage: CharacterStage,
    error: Exception,
): CharacterLoadDiagnostic {
    if (error is CharacterSourceFailure) return error.diagnostic
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    val httpCode = causes.filterIsInstance<HttpException>().firstOrNull()?.code()
        ?: causes.filterIsInstance<TenraiHttpException>().firstOrNull()?.code
    val reason = when {
        httpCode != null -> CharacterLoadReason.HTTP_ERROR
        causes.any { it is InterruptedIOException } -> CharacterLoadReason.TIMEOUT
        causes.any { it is UnknownHostException } -> CharacterLoadReason.DNS
        causes.any { it is SSLException } -> CharacterLoadReason.TLS
        causes.any { it is JsonParseException || it is TenraiProtocolException || it is IllegalArgumentException || it is IllegalStateException } ->
            CharacterLoadReason.INVALID_RESPONSE
        causes.any { it is IOException || it is TenraiTransportException } -> CharacterLoadReason.NETWORK
        else -> CharacterLoadReason.UNKNOWN
    }
    return CharacterLoadDiagnostic(source, stage, CharacterLoadState.ERROR, reason, httpCode)
}

internal fun emitCharacterDiagnostic(sink: (CharacterLoadDiagnostic) -> Unit, value: CharacterLoadDiagnostic) {
    // A diagnostics consumer must never break character loading.
    try { sink(value) } catch (_: Exception) { }
}

internal suspend fun <T> characterRequest(
    source: CharacterSource,
    stage: CharacterStage,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    throw CharacterSourceFailure(characterFailure(source, stage, error), error)
}

/** Reports the actual HTTP status, including 200 and 500. Does not consume or log response bodies. */
internal class CharacterHttpDiagnosticsInterceptor(
    private val source: CharacterSource,
    private val sink: (CharacterLoadDiagnostic) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val stage = when (source) {
            CharacterSource.ANIMETKA -> when {
                path == "/api/anime/search" -> CharacterStage.SEARCH
                path.matches(Regex("/api/anime/[0-9]+/characters")) -> CharacterStage.CHARACTERS
                path.matches(Regex("/api/anime/characters/[0-9]+")) -> CharacterStage.DETAILS
                path.matches(Regex("/api/anime/[0-9]+")) -> CharacterStage.DETAILS
                else -> null
            }
            CharacterSource.SHIKIMORI -> CharacterStage.GRAPHQL.takeIf { path == "/api/graphql" }
            CharacterSource.SHIKIMORI_IO -> CharacterStage.POSTER.takeIf { request.url.host == "shikimori.io" }
            CharacterSource.TENRAI -> null
        } ?: return chain.proceed(request)
        return try {
            val response = chain.proceed(request)
            emitCharacterDiagnostic(sink, CharacterLoadDiagnostic(
                source, stage,
                if (response.isSuccessful) CharacterLoadState.SUCCESS else CharacterLoadState.ERROR,
                if (response.isSuccessful) CharacterLoadReason.OK else CharacterLoadReason.HTTP_ERROR,
                httpCode = response.code,
            ))
            response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitCharacterDiagnostic(sink, characterFailure(source, stage, error))
            throw error
        }
    }
}
