package com.sergey.animevault.data.community

import com.sergey.animevault.BuildConfig
import java.util.UUID

data class CommunityConfiguration(
    val url: String = BuildConfig.SUPABASE_URL,
    val key: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    val enabled: Boolean = BuildConfig.COMMUNITY_ENABLED,
) {
    val ready: Boolean get() = enabled && url == "https://kgvkxocbkklisqrqmcjx.supabase.co" &&
        key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))
    companion object {
        const val REDIRECT = "animevault://auth/callback"
    }
}

// Snake case is the explicit PostgREST wire contract. Keep DTOs through R8.
data class CommunityCommentDto(
    val id: String, val parent_id: String?, val author_id: String?, val username: String,
    val display_name: String, val avatar_path: String?, val body: String, val status: String,
    val contains_spoiler: Boolean, val created_at: String, val updated_at: String,
    val likes: Int, val liked: Boolean, val reply_count: Int,
)
data class CommunityPageDto(val comments: List<CommunityCommentDto>, val total: Int)
data class CommunityProfileDto(
    val user_id: String, val username: String, val display_name: String, val avatar_path: String?,
    val bio: String?, val created_at: String,
)
data class CommunityStatsDto(val comments: Int, val likes: Int)
data class CommunityContextDto(val anime_id: String, val episode_id: String?, val episode_key: String?, val episode_number: Int?) {
    val key: String
        get() {
            return "$anime_id:${episode_id ?: "general"}"
        }
}
data class CommunityIdentity(
    val anilist: Long? = null, val mal: Long? = null, val provider: String? = null,
    val external: String? = null, val episodeExternal: String? = null,
) {
    init {
        require(anilist == null || anilist > 0)
        require(mal == null || mal > 0)
    }
}
data class CommunitySessionDto(val access_token: String, val refresh_token: String, val expires_in: Long, val user: CommunityUserDto)
data class CommunityUserDto(val id: String)
data class StoredCommunitySessionDto(val access: String, val refresh: String, val expires: Long, val user: String, val recovery: Boolean = false)
data class CommunityPendingAuthDto(val verifier: String, val purpose: String, val created: Long, val email: String? = null)
data class CommunityDraftDto(val body: String = "", val spoiler: Boolean = false, val request: String = UUID.randomUUID().toString())

class CommunityFailure(val kind: String) : Exception(kind)
fun communityMessage(error: Throwable): String = when ((error as? CommunityFailure)?.kind) {
    "configuration" -> "Сервис не настроен"
    "offline" -> "Нет связи. Проверьте подключение и повторите попытку."
    "unauthorized", "session" -> "Сессия сообщества истекла. Войдите снова."
    "banned" -> "Действие недоступно: аккаунт сообщества заблокирован."
    "profile" -> "Создайте профиль сообщества: откройте «Мой профиль», задайте никнейм и сохраните его."
    "email" -> "Подтвердите email кодом из письма."
    "credentials" -> "Неверный email или пароль."
    "forbidden" -> "Действие недоступно. Проверьте профиль и права аккаунта."
    "rate" -> "Слишком много запросов. Подождите и повторите попытку."
    "invalid" -> "Проверьте введённые данные. Никнейм мог быть занят, а ссылка — истечь или уже использоваться."
    "callback" -> "Ссылка недействительна или истекла. Запросите новое письмо на этом устройстве."
    "server" -> "Сервис временно недоступен. Попробуйте позже."
    else -> "Не удалось выполнить действие. Повторите попытку."
}

fun normalizeCommunityBody(body: String): String = body.replace("\r\n", "\n").replace('\r', '\n').trim(' ', '\t', '\n')
fun validCommunityPassword(password: String): Boolean = password.length >= 12 && password.any(Char::isLetter) && password.any(Char::isDigit)

internal fun communityFailureKind(status: Int, payload: String): String = when {
    status==429 || payload.contains("over_email_send_rate_limit") -> "rate"
    payload.contains("otp_expired") -> "callback"
    payload.contains("email_not_confirmed") -> "email"
    payload.contains("invalid_credentials") -> "credentials"
    status==403 && payload.contains("community_profile_required") -> "profile"
    status==401 -> "unauthorized"
    status==403 && payload.contains("community_banned") -> "banned"
    status==403 -> "forbidden"
    status>=500 || status==404 -> "server"
    else -> "invalid"
}
