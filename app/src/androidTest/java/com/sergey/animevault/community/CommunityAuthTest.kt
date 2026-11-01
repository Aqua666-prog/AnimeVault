package com.sergey.animevault.community

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonElement
import com.sergey.animevault.data.community.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic Auth responses verify client flow; real Supabase delivery/callback still requires device QA. */
class CommunityAuthTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sessions: CommunitySessionManager
    private lateinit var api: FakeCommunityApi
    private lateinit var auth: CommunityAuthRepository
    @Before fun prepare() {
        sessions=CommunitySessionManager(context).also {it.clear()}
        api=FakeCommunityApi()
        auth=CommunityAuthRepository(api,sessions,CommunityCache(context))
    }
    @Test fun emailOtpLoginAndSignupUseExplicitIntentAndPersistSession() = runBlocking {
        auth.requestEmailOtp("team@example.invalid")
        assertEquals(false,api.lastBody["create_user"])
        assertEquals("email",sessions.pending()!!.purpose)
        try {auth.verifyOtp("other@example.invalid","12345678");fail("different email accepted")}
        catch(e: CommunityFailure) {assertEquals("callback",e.kind)}
        auth.verifyOtp("team@example.invalid","12345678")
        assertNotNull(CommunitySessionManager(context).session.value)
        assertEquals("email",api.lastBody["type"])
        auth.signOut()
        auth.requestEmailOtp("new@example.invalid",createUser=true)
        assertEquals(true,api.lastBody["create_user"])
    }
    @Test fun registrationConfirmationAndReplayProtection() = runBlocking {
        auth.register("team@example.invalid","LongPassword123")
        assertNull(sessions.session.value)
        assertEquals(CommunityConfiguration.REDIRECT,api.lastQuery["redirect_to"])
        assertEquals("s256",api.lastBody["code_challenge_method"])
        val verifier=sessions.pending()!!.verifier
        assertEquals(CommunitySessionManager.challenge(verifier),api.lastBody["code_challenge"])
        auth.callback(Uri.parse("animevault://auth/callback?code=synthetic-valid-code"))
        assertNotNull(sessions.session.value)
        assertNull(sessions.pending())
        try { auth.callback(Uri.parse("animevault://auth/callback?code=synthetic-valid-code")); fail("replay accepted") }
        catch(e: CommunityFailure) {assertEquals("callback",e.kind)}
    }
    @Test fun loginLogoutAndFirebaseIsolation() = runBlocking {
        val firebase = com.google.firebase.auth.FirebaseAuth.getInstance()
        val before = firebase.currentUser?.uid
        auth.signIn("team@example.invalid","LongPassword123")
        assertNotNull(sessions.session.value)
        auth.signOut()
        assertNull(sessions.session.value)
        assertEquals(before,firebase.currentUser?.uid)
        auth.signIn("team@example.invalid","LongPassword123")
        assertNotNull(sessions.session.value)
    }
    @Test fun recoveryOtpAndPasswordUpdate() = runBlocking {
        auth.recover("team@example.invalid")
        assertEquals(CommunityConfiguration.REDIRECT,api.lastQuery["redirect_to"])
        auth.verifyOtp("team@example.invalid","12345678")
        assertTrue(sessions.recovery.value)
        assertTrue(CommunitySessionManager(context).recovery.value)
        auth.updatePassword("NewLongPassword123","")
        assertEquals("PUT",api.lastMethod)
        assertFalse(sessions.recovery.value)
    }
    @Test fun expiredSessionRefreshIsSerialized() = runBlocking {
        sessions.save(api.session(expires=0))
        coroutineScope { (1..10).map { async {auth.token()} }.awaitAll() }
        assertEquals(1,api.refreshCount)
        assertNotNull(sessions.session.value)
    }
    @Test fun networkFailureRetainsSessionButRevokedRefreshClearsIt() = runBlocking {
        sessions.save(api.session(expires=0))
        api.failure="offline"
        try {auth.token();fail("offline refresh accepted")} catch(e: CommunityFailure) {assertEquals("offline",e.kind)}
        assertNotNull(sessions.session.value)
        api.failure="invalid"
        try {auth.token();fail("revoked refresh accepted")} catch(e: CommunityFailure) {assertEquals("session",e.kind)}
        assertNull(sessions.session.value)
    }
    @Test fun maliciousAndExpiredRedirectsAreRejected() = runBlocking {
        auth.recover("team@example.invalid")
        val pending=sessions.pending()!!
        for (link in listOf("https://auth/callback?code=synthetic-valid-code", "animevault://auth/wrong?code=synthetic-valid-code",
            "animevault://auth/callback#access_token=malicious", "animevault://auth/callback?error=access_denied",
            "animevault://auth/callback?code=synthetic-valid-code&code=duplicate-code")) {
            try {auth.callback(Uri.parse(link));fail("malicious redirect accepted")} catch(e: CommunityFailure) {assertEquals("callback",e.kind)}
        }
        sessions.savePending(pending.copy(created=System.currentTimeMillis()-3_700_000))
        try {auth.callback(Uri.parse("animevault://auth/callback?code=synthetic-valid-code"));fail("expired redirect accepted")}
        catch(e: CommunityFailure) {assertEquals("callback",e.kind)}
    }
    @Test fun actionCannotMoveToAnotherCommunitySession() = runBlocking {
        auth.signIn("team@example.invalid","LongPassword123")
        val previous=sessions.session.value!!.user
        sessions.save(api.session().copy(user=CommunityUserDto("00000000-0000-0000-0000-000000000002")))
        try {auth.tokenFor(previous);fail("cross-account action accepted")} catch(e: CommunityFailure) {assertEquals("session",e.kind)}
    }
    @Test fun keystoreSessionRestoresAfterProcessRecreation() {
        sessions.save(api.session())
        assertEquals(sessions.session.value?.user,CommunitySessionManager(context).session.value?.user)
    }
}
private class FakeCommunityApi : CommunityApi(CommunityConfiguration(key="sb_publishable_synthetic",enabled=true),OkHttpClient()) {
    var lastQuery=emptyMap<String,String>()
    var lastBody=emptyMap<String,Any?>()
    var lastMethod=""
    var failure: String?=null
    var refreshCount=0
    fun session(expires: Long=3600) = CommunitySessionDto("synthetic-access","synthetic-refresh",expires,CommunityUserDto("00000000-0000-0000-0000-000000000001"))
    override suspend fun request(path: String,body: Any?,token: String?,query: Map<String,String>,method: String): JsonElement {
        failure?.let {throw CommunityFailure(it)}
        lastQuery=query;lastMethod=method
        @Suppress("UNCHECKED_CAST")
        lastBody=body as? Map<String,Any?> ?: emptyMap()
        if(query["grant_type"]=="refresh_token") {refreshCount++;delay(20)}
        return if(path=="auth/v1/token" || path=="auth/v1/verify") gson.toJsonTree(session()) else com.google.gson.JsonNull.INSTANCE
    }
}
