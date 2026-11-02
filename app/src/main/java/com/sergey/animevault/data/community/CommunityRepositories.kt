package com.sergey.animevault.data.community

import android.content.Context
import com.google.gson.JsonElement
import okhttp3.OkHttpClient

class CommunityServices(context: Context, client: OkHttpClient) {
    val configuration = CommunityConfiguration()
    val sessions = CommunitySessionManager(context)
    val cache = CommunityCache(context)
    val api = CommunityApi(configuration, client)
    val auth = CommunityAuthRepository(api, sessions, cache)
    val identity = AnimeIdentityResolver(api, cache)
    val profiles = CommunityProfileRepository(api, auth)
    val comments = CommentsRepository(api, auth, cache)
    val likes = CommentLikesRepository(api, auth)
    val moderation = ModerationRepository(api, auth)
}

class AnimeIdentityResolver(private val api: CommunityApi, private val cache: CommunityCache) {
    private fun key(identity: CommunityIdentity) = "identity:" + api.gson.toJson(identity)
    private val provisioning = kotlinx.coroutines.sync.Mutex()
    private val autoGate = CommunityProvisioningGate()
    suspend fun resolve(identity: CommunityIdentity): CommunityContextDto? {
        val request = mapOf("p_anilist" to identity.anilist, "p_mal" to identity.mal, "p_provider" to identity.provider,
            "p_external" to identity.external, "p_episode_external" to identity.episodeExternal)
        var context: CommunityContextDto? = api.rpc("community_resolve", request)
        if (context == null && autoGate.supports(identity)) {
            provisioning.lock()
            try {
                // Other title/source requests may have completed while we waited. No recursive retries.
                context = api.rpc("community_resolve", request)
                val identityKey = key(identity)
                if (context == null && autoGate.claim(identityKey)) {
                    try {
                        val ensured = api.request("functions/v1/community-ensure", request)
                        // Never trust a UUID in an Intent or function response: re-resolve through the existing RPC.
                        context = api.rpc("community_resolve", request)
                        // A concurrent provisioner may hold the lease. Bounded READ-only checks, never another create.
                        val status = if (ensured.isJsonObject) ensured.asJsonObject.get("status") else null
                        val pending = status?.isJsonPrimitive == true && status.asJsonPrimitive.isString && status.asString == "pending"
                        if (pending) {
                            for (waitMs in listOf(750L, 1_500L)) {
                                if (context != null) break
                                kotlinx.coroutines.delay(waitMs)
                                context = api.rpc("community_resolve", request)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: CommunityFailure) {
                        // Old deployments may not have the function; preserve the existing unavailable state.
                        if (e.kind == "offline") throw e
                    }
                }
            } finally { provisioning.unlock() }
        }
        cache.put(key(identity), context ?: com.google.gson.JsonNull.INSTANCE)
        return context
    }
    suspend fun cached(identity: CommunityIdentity): CommunityContextDto? = cache.get(key(identity), CommunityContextDto::class.java)
}
class CommunityProfileRepository(private val api: CommunityApi, private val auth: CommunityAuthRepository) {
    suspend fun publicProfile(user: String): CommunityProfileDto? {
        java.util.UUID.fromString(user)
        val response = api.request("rest/v1/community_profiles", method = "GET", query = mapOf("user_id" to "eq.$user", "select" to "*", "limit" to "1"))
        return response.asJsonArray.firstOrNull()?.let { api.gson.fromJson(it, CommunityProfileDto::class.java) }
    }
    suspend fun own(): CommunityProfileDto? {
        val token = auth.token() ?: return null
        val user = auth.sessions.session.value?.user ?: return null
        val response = api.request("rest/v1/community_profiles", token = token, method = "GET", query = mapOf("user_id" to "eq.$user", "select" to "*", "limit" to "1"))
        return response.asJsonArray.firstOrNull()?.let { api.gson.fromJson(it, CommunityProfileDto::class.java) }
    }
    suspend fun save(username: String, name: String, bio: String, avatar: String, expectedUser: String): CommunityProfileDto = api.rpc("community_save_profile",
        mapOf("p_username" to username, "p_display_name" to name, "p_bio" to bio, "p_avatar" to avatar), auth.tokenFor(expectedUser))
    suspend fun stats(user: String): CommunityStatsDto = api.rpc("community_profile_stats", mapOf("p_user" to user))
}
class CommentsRepository(private val api: CommunityApi, private val auth: CommunityAuthRepository, private val cache: CommunityCache) {
    private fun key(context: CommunityContextDto, parent: String?, sort: String, offset: Int) = "page:${context.key}:$parent:$sort:$offset"
    suspend fun page(context: CommunityContextDto, parent: String?, sort: String, offset: Int): CommunityPageDto {
        val page: CommunityPageDto = api.rpc("community_list_comments", mapOf("p_anime" to context.anime_id,
            "p_episode" to context.episode_id, "p_parent" to parent, "p_sort" to sort, "p_offset" to offset, "p_limit" to 20), auth.token())
        cache.put(key(context,parent,sort,offset), page.copy(comments = page.comments.map { it.copy(liked = false) }))
        return page
    }
    suspend fun cached(context: CommunityContextDto, parent: String?, sort: String, offset: Int): CommunityPageDto? = cache.get(key(context,parent,sort,offset), CommunityPageDto::class.java)
    suspend fun create(context: CommunityContextDto, parent: String?, draft: CommunityDraftDto, expectedUser: String): String = api.rpc("community_create_comment", mapOf(
        "p_anime" to context.anime_id, "p_episode" to context.episode_id, "p_parent" to parent,
        "p_body" to normalizeCommunityBody(draft.body), "p_spoiler" to draft.spoiler, "p_request" to draft.request), auth.tokenFor(expectedUser))
    suspend fun edit(id: String, body: String, spoiler: Boolean, expectedUser: String) { api.rpc<JsonElement>("community_edit_comment", mapOf("p_id" to id,"p_body" to normalizeCommunityBody(body),"p_spoiler" to spoiler),auth.tokenFor(expectedUser)); cache.clearPages() }
    suspend fun delete(id: String, expectedUser: String) { api.rpc<JsonElement>("community_delete_comment",mapOf("p_id" to id),auth.tokenFor(expectedUser)); cache.clearPages() }
}
class CommentLikesRepository(private val api: CommunityApi, private val auth: CommunityAuthRepository) {
    suspend fun set(id: String, liked: Boolean, expectedUser: String) { api.rpc<JsonElement>("community_set_like",mapOf("p_id" to id,"p_liked" to liked),auth.tokenFor(expectedUser)) }
}
class ModerationRepository(private val api: CommunityApi, private val auth: CommunityAuthRepository) {
    suspend fun report(id: String, reason: String, description: String, expectedUser: String) { api.rpc<JsonElement>("community_report",mapOf("p_id" to id,"p_reason" to reason,"p_description" to description),auth.tokenFor(expectedUser)) }
}
