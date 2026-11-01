package com.sergey.animevault.data.community

import android.net.Uri
import android.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CommunityAuthRepository(private val api: CommunityApi, val sessions: CommunitySessionManager, private val cache: CommunityCache) {
    private val mutex = Mutex()
    private suspend fun <T> locked(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        mutex.withLock { block() }
    }
    suspend fun token(): String? = locked {
        val stored = sessions.session.value ?: return@locked null
        if (stored.expires > System.currentTimeMillis() + 60_000) return@locked stored.access
        try {
            val response = api.request("auth/v1/token", mapOf("refresh_token" to stored.refresh), query = mapOf("grant_type" to "refresh_token"))
            val session = api.gson.fromJson(response, CommunitySessionDto::class.java)
            sessions.save(session)
            session.access_token
        } catch (e: CommunityFailure) {
            if (e.kind in setOf("invalid", "unauthorized", "forbidden")) {
                sessions.clear(); cache.clearDrafts(stored.user); sessions.notice.value = communityMessage(CommunityFailure("session")); throw CommunityFailure("session")
            }
            throw e
        }
    }
    suspend fun tokenFor(expectedUser: String): String {
        val access=token() ?: throw CommunityFailure("unauthorized")
        if (sessions.session.value?.user != expectedUser) throw CommunityFailure("session")
        return access
    }
    suspend fun reauthenticate() {
        val user=sessions.session.value?.user ?: throw CommunityFailure("unauthorized")
        api.request("auth/v1/reauthenticate",token=tokenFor(user),method="GET")
    }
    suspend fun signIn(email: String, password: String) = locked {
        val response = api.request("auth/v1/token", mapOf("email" to email.trim(), "password" to password), query = mapOf("grant_type" to "password"))
        sessions.save(api.gson.fromJson(response, CommunitySessionDto::class.java), isRecovery=false)
        sessions.clearPending()
    }
    suspend fun requestEmailOtp(email: String, createUser: Boolean = false) = locked {
        if (email.trim().isEmpty()) throw CommunityFailure("invalid")
        val pending=begin("email",email)
        try {
            api.request("auth/v1/otp",mapOf("email" to email.trim(),"create_user" to createUser,
                "code_challenge" to CommunitySessionManager.challenge(pending.verifier),"code_challenge_method" to "s256"),
                query=mapOf("redirect_to" to CommunityConfiguration.REDIRECT))
        } catch(e: Exception) {
            if(e is CommunityFailure && e.kind!="offline") sessions.clearPending()
            throw e
        }
    }
    suspend fun register(email: String, password: String, captchaToken: String? = null) = locked {
        if (!validCommunityPassword(password)) throw CommunityFailure("invalid")
        val pending = begin("signup",email)
        try {
            api.request("auth/v1/signup", mapOf(
                "email" to email.trim(), "password" to password,
                "code_challenge" to CommunitySessionManager.challenge(pending.verifier), "code_challenge_method" to "s256",
                "gotrue_meta_security" to mapOf("captcha_token" to captchaToken),
            ), query = mapOf("redirect_to" to CommunityConfiguration.REDIRECT))
        } catch (e: Exception) {
            if (e is CommunityFailure && e.kind != "offline") sessions.clearPending()
            throw e
        }
    }
    suspend fun recover(email: String, captchaToken: String? = null) = locked {
        val pending = begin("recovery",email)
        try {
            api.request("auth/v1/recover", mapOf("email" to email.trim(),
                "code_challenge" to CommunitySessionManager.challenge(pending.verifier), "code_challenge_method" to "s256",
                "gotrue_meta_security" to mapOf("captcha_token" to captchaToken)),
                query = mapOf("redirect_to" to CommunityConfiguration.REDIRECT))
        } catch (e: Exception) {
            if (e is CommunityFailure && e.kind != "offline") sessions.clearPending()
            throw e
        }
    }
    private fun begin(purpose: String, email: String): CommunityPendingAuthDto {
        val random = ByteArray(48).also { SecureRandom().nextBytes(it) }
        return CommunityPendingAuthDto(Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), purpose, System.currentTimeMillis(),email.trim().lowercase(java.util.Locale.ROOT)).also(sessions::savePending)
    }
    /** Only authorization code + locally retained PKCE verifier; never accept Intent access/refresh tokens. */
    suspend fun callback(uri: Uri): Unit = locked {
        if (uri.scheme != "animevault" || uri.host != "auth" || uri.path != "/callback" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) throw CommunityFailure("callback")
        val pending = sessions.pending() ?: throw CommunityFailure("callback")
        if (System.currentTimeMillis() - pending.created !in 0..3_600_000L || pending.purpose !in setOf("signup","recovery","email")) throw CommunityFailure("callback")
        if (uri.getQueryParameter("error") != null) throw CommunityFailure("callback")
        val code = uri.getQueryParameters("code").singleOrNull()?.takeIf { it.length in 10..4096 } ?: throw CommunityFailure("callback")
        val response = api.request("auth/v1/token", mapOf("auth_code" to code, "code_verifier" to pending.verifier), query = mapOf("grant_type" to "pkce"))
        sessions.save(api.gson.fromJson(response, CommunitySessionDto::class.java), isRecovery=pending.purpose == "recovery")
        sessions.clearPending()
        sessions.notice.value = if (sessions.recovery.value) "Ссылка подтверждена. Откройте аккаунт сообщества и задайте новый пароль." else "Email подтверждён. Откройте аккаунт сообщества и создайте профиль."
    }
    /** OTP fallback needs the same pending request, explicit purpose and email; never reads tokens from a link. */
    suspend fun verifyOtp(email: String, otp: String) = locked {
        val pending = sessions.pending() ?: throw CommunityFailure("callback")
        if (pending.email!=null && pending.email!=email.trim().lowercase(java.util.Locale.ROOT)) throw CommunityFailure("callback")
        if (!otp.matches(Regex("[0-9]{8}")) || System.currentTimeMillis()-pending.created !in 0..3_600_000L) throw CommunityFailure("callback")
        val response = api.request("auth/v1/verify", mapOf("email" to email.trim(), "token" to otp, "type" to pending.purpose))
        sessions.save(api.gson.fromJson(response, CommunitySessionDto::class.java), isRecovery=pending.purpose == "recovery"); sessions.clearPending()
    }
    suspend fun updatePassword(password: String, currentPassword: String, nonce: String = "") {
        if (!validCommunityPassword(password)) throw CommunityFailure("invalid")
        val user=sessions.session.value?.user ?: throw CommunityFailure("unauthorized")
        val access = tokenFor(user)
        val body=mutableMapOf("password" to password)
        if (currentPassword.isNotBlank()) body["current_password"]=currentPassword
        if (nonce.isNotBlank()) body["nonce"]=nonce
        api.request("auth/v1/user", body, token = access, method = "PUT")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sessions.clearRecovery() }
    }
    suspend fun signOut() = locked {
        val previous = sessions.session.value
        // Local logout always succeeds, even offline. Revocation failure is reported without retaining credentials.
        try { previous?.let { api.request("auth/v1/logout", token = it.access, query = mapOf("scope" to "local")) } }
        finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                sessions.clear(); previous?.let { cache.clearDrafts(it.user) }
            }
        }
    }
}
