package com.sergey.animevault.data.anilist

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.google.gson.Gson
import com.sergey.animevault.data.metadata.AniListMetadataRepository
import com.sergey.animevault.data.online.SecureSessionStore
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList account integration for a mobile-only client.
 *
 * AnimeVault deliberately uses AniList's implicit OAuth grant: there is no client
 * secret to ship in the APK. The access token is encrypted with Android Keystore
 * through [SecureSessionStore].
 */
class AniListSyncRepository(
    context: Context,
    private val metadataRepository: AniListMetadataRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureStore = SecureSessionStore(appContext)
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<AniListAccountState> = _state.asStateFlow()

    val clientId: String?
        get() = preferences.getString(KEY_CLIENT_ID, null)?.trim()?.takeIf(String::isNotBlank)

    val isConfigured: Boolean get() = clientId != null
    val isAuthenticated: Boolean get() = !accessToken().isNullOrBlank()

    fun setClientId(value: String) {
        val clean = value.trim().filter(Char::isDigit)
        if (clean.isBlank()) preferences.edit { remove(KEY_CLIENT_ID) }
        else preferences.edit { putString(KEY_CLIENT_ID, clean) }
        _state.value = loadInitialState()
    }

    fun authorizationUri(): Uri? {
        val id = clientId ?: return null
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "token")
            .build()
    }

    /** Returns true only when the URI belongs to AnimeVault's AniList callback. */
    fun handleOAuthRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != REDIRECT_SCHEME || uri.host != REDIRECT_HOST) return false
        val params = parseFragmentParameters(uri.fragment)
        val token = params["access_token"]?.trim()?.takeIf(String::isNotBlank)
        val error = params["error"] ?: params["error_description"]
        when {
            token != null -> {
                secureStore.put(KEY_ACCESS_TOKEN, token)
                _state.value = AniListAccountState.Connected(viewer = null, syncing = false)
            }
            !error.isNullOrBlank() -> _state.value = AniListAccountState.Error(error)
            else -> _state.value = AniListAccountState.Error("AniList не вернул access token")
        }
        return true
    }

    suspend fun refreshViewer(): AniListViewer? {
        val token = accessToken()
        if (token.isNullOrBlank()) {
            _state.value = loadInitialState()
            return null
        }
        _state.value = AniListAccountState.Connected(
            viewer = (_state.value as? AniListAccountState.Connected)?.viewer,
            syncing = true,
        )
        return runCatching {
            val json = authenticatedGraphQl(token, VIEWER_QUERY, emptyMap())
            parseViewerResponse(gson, json)
        }.fold(
            onSuccess = { viewer ->
                _state.value = AniListAccountState.Connected(viewer = viewer, syncing = false)
                viewer
            },
            onFailure = { error ->
                if (error.message.orEmpty().contains("401") || error.message.orEmpty().contains("Unauthorized", true)) {
                    secureStore.put(KEY_ACCESS_TOKEN, null)
                }
                _state.value = AniListAccountState.Error(error.message ?: "Не удалось проверить AniList")
                null
            },
        )
    }

    fun signOut() {
        secureStore.put(KEY_ACCESS_TOKEN, null)
        _state.value = loadInitialState()
    }

    /**
     * Writes only monotonic episode progress by default. This prevents a seek or an
     * older local copy from accidentally rolling back the user's AniList list.
     */
    suspend fun syncEpisodeProgress(
        anilistId: Long,
        watchedEpisode: Int,
        episodeCount: Int? = null,
        forceCompleted: Boolean = false,
    ): AniListMediaListEntry? {
        if (anilistId <= 0L || watchedEpisode <= 0) return null
        val token = accessToken() ?: return null
        val safeProgress = watchedEpisode.coerceAtLeast(1)
        val completed = forceCompleted || (episodeCount != null && episodeCount > 0 && safeProgress >= episodeCount)
        val previous = queryMediaListEntry(token, anilistId)
        if (!completed && previous?.progress != null && previous.progress >= safeProgress) return previous
        if (completed && previous?.status == AniListListStatus.COMPLETED && (previous.progress ?: 0) >= safeProgress) {
            return previous
        }
        val variables = mutableMapOf<String, Any>(
            "mediaId" to anilistId,
            "progress" to safeProgress,
            "status" to if (completed) AniListListStatus.COMPLETED.name else AniListListStatus.CURRENT.name,
        )
        val json = authenticatedGraphQl(token, SAVE_PROGRESS_MUTATION, variables)
        return parseMediaListMutationResponse(gson, json)
    }

    suspend fun updateListEntry(
        anilistId: Long,
        status: AniListListStatus,
        progress: Int? = null,
        score: Double? = null,
    ): AniListMediaListEntry? {
        val token = accessToken() ?: error("Сначала войдите в AniList")
        val variables = linkedMapOf<String, Any>(
            "mediaId" to anilistId,
            "status" to status.name,
        )
        progress?.coerceAtLeast(0)?.let { variables["progress"] = it }
        score?.coerceIn(0.0, 10.0)?.let { variables["score"] = it }
        val json = authenticatedGraphQl(token, SAVE_LIST_MUTATION, variables)
        return parseMediaListMutationResponse(gson, json)
    }

    suspend fun getListEntry(anilistId: Long): AniListMediaListEntry? {
        val token = accessToken() ?: return null
        return queryMediaListEntry(token, anilistId)
    }

    suspend fun resolveAniListId(anilistId: Long?, malId: Long?): Long? {
        if (anilistId != null && anilistId > 0) return anilistId
        return malId?.takeIf { it > 0 }?.let { metadataRepository.findAnimeByMalId(it)?.anilistId }
    }

    private suspend fun queryMediaListEntry(token: String, mediaId: Long): AniListMediaListEntry? {
        val json = authenticatedGraphQl(token, LIST_ENTRY_QUERY, mapOf("mediaId" to mediaId))
        return parseMediaListQueryResponse(gson, json)
    }

    private suspend fun authenticatedGraphQl(token: String, query: String, variables: Map<String, Any>): String {
        val payload = GraphQlRequest(query = query, variables = variables)
        val request = Request.Builder()
            .url(GRAPHQL_URL)
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; AniList sync"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        return client.executeText(request, "AniList")
    }

    private fun accessToken(): String? = secureStore.get(KEY_ACCESS_TOKEN)

    private fun loadInitialState(): AniListAccountState = when {
        clientId == null -> AniListAccountState.NotConfigured
        accessToken().isNullOrBlank() -> AniListAccountState.SignedOut
        else -> AniListAccountState.Connected(viewer = null, syncing = false)
    }

    companion object {
        const val REDIRECT_SCHEME = "animevault"
        const val REDIRECT_HOST = "anilist-auth"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"

        private const val PREFERENCES_NAME = "anilist_sync"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "anilist.access_token"
        private const val GRAPHQL_URL = "https://graphql.anilist.co"
        private const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val VIEWER_QUERY = """
            query AnimeVaultViewer {
              Viewer { id name avatar { medium } }
            }
        """

        private const val LIST_ENTRY_QUERY = """
            query AnimeVaultListEntry(${'$'}mediaId: Int!) {
              Media(id: ${'$'}mediaId, type: ANIME) {
                mediaListEntry { id mediaId status progress score repeat updatedAt }
              }
            }
        """

        private const val SAVE_PROGRESS_MUTATION = """
            mutation AnimeVaultSaveProgress(
              ${'$'}mediaId: Int!,
              ${'$'}status: MediaListStatus!,
              ${'$'}progress: Int!
            ) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress) {
                id mediaId status progress score repeat updatedAt
              }
            }
        """

        private const val SAVE_LIST_MUTATION = """
            mutation AnimeVaultSaveList(
              ${'$'}mediaId: Int!,
              ${'$'}status: MediaListStatus!,
              ${'$'}progress: Int,
              ${'$'}score: Float
            ) {
              SaveMediaListEntry(
                mediaId: ${'$'}mediaId,
                status: ${'$'}status,
                progress: ${'$'}progress,
                score: ${'$'}score
              ) {
                id mediaId status progress score repeat updatedAt
              }
            }
        """
    }
}

sealed interface AniListAccountState {
    data object NotConfigured : AniListAccountState
    data object SignedOut : AniListAccountState
    data class Connected(val viewer: AniListViewer?, val syncing: Boolean) : AniListAccountState
    data class Error(val message: String) : AniListAccountState
}

data class AniListViewer(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
)

enum class AniListListStatus {
    CURRENT,
    PLANNING,
    COMPLETED,
    DROPPED,
    PAUSED,
    REPEATING,
}

data class AniListMediaListEntry(
    val id: Long,
    val mediaId: Long,
    val status: AniListListStatus?,
    val progress: Int?,
    val score: Double?,
    val repeat: Int?,
    val updatedAt: Long?,
)

internal fun parseFragmentParameters(fragment: String?): Map<String, String> = fragment
    .orEmpty()
    .split('&')
    .mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = Uri.decode(part.substring(0, index))
        val value = Uri.decode(part.substring(index + 1))
        key to value
    }
    .toMap()

internal fun parseViewerResponse(gson: Gson, json: String): AniListViewer? {
    val response = gson.fromJson(json, ViewerResponse::class.java) ?: return null
    response.errors.orEmpty().firstOrNull()?.message?.let { error(it) }
    val viewer = response.data?.viewer ?: return null
    return AniListViewer(
        id = viewer.id ?: return null,
        name = viewer.name?.takeIf(String::isNotBlank) ?: return null,
        avatarUrl = viewer.avatar?.medium?.takeIf(String::isNotBlank),
    )
}

internal fun parseMediaListQueryResponse(gson: Gson, json: String): AniListMediaListEntry? {
    val response = gson.fromJson(json, MediaListQueryResponse::class.java) ?: return null
    response.errors.orEmpty().firstOrNull()?.message?.let { error(it) }
    return response.data?.media?.mediaListEntry?.toDomain()
}

internal fun parseMediaListMutationResponse(gson: Gson, json: String): AniListMediaListEntry? {
    val response = gson.fromJson(json, MediaListMutationResponse::class.java) ?: return null
    response.errors.orEmpty().firstOrNull()?.message?.let { error(it) }
    return response.data?.saveMediaListEntry?.toDomain()
}

private fun MediaListEntryDto.toDomain(): AniListMediaListEntry? = AniListMediaListEntry(
    id = id ?: return null,
    mediaId = mediaId ?: return null,
    status = status?.let { runCatching { AniListListStatus.valueOf(it) }.getOrNull() },
    progress = progress,
    score = score,
    repeat = repeat,
    updatedAt = updatedAt,
)

private data class GraphQlRequest(val query: String, val variables: Map<String, Any>)
private data class GraphQlError(val message: String? = null)
private data class AvatarDto(val medium: String? = null)
private data class ViewerDto(val id: Long? = null, val name: String? = null, val avatar: AvatarDto? = null)
private data class ViewerData(val viewer: ViewerDto? = null)
private data class ViewerResponse(val data: ViewerData? = null, val errors: List<GraphQlError>? = null)
private data class MediaListEntryDto(
    val id: Long? = null,
    val mediaId: Long? = null,
    val status: String? = null,
    val progress: Int? = null,
    val score: Double? = null,
    val repeat: Int? = null,
    val updatedAt: Long? = null,
)
private data class MediaListMediaDto(val mediaListEntry: MediaListEntryDto? = null)
private data class MediaListQueryData(val media: MediaListMediaDto? = null)
private data class MediaListQueryResponse(val data: MediaListQueryData? = null, val errors: List<GraphQlError>? = null)
private data class MediaListMutationData(val saveMediaListEntry: MediaListEntryDto? = null)
private data class MediaListMutationResponse(val data: MediaListMutationData? = null, val errors: List<GraphQlError>? = null)
