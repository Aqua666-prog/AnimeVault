package com.sergey.animevault.data.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.sergey.animevault.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** Application-scoped; Firebase owns persistence and token refresh. No local library access. */
class FirebaseAccountRepository(context: Context) : AccountRepository {
    private val auth: FirebaseAuth? = if (BuildConfig.FIREBASE_AUTH_CONFIGURED) {
        FirebaseApp.initializeApp(context.applicationContext)?.let(FirebaseAuth::getInstance)
    } else {
        null
    }
    override val configured: Boolean = auth != null
    private val mutableUser = MutableStateFlow(auth?.currentUser?.toAccountUser())
    override val user: StateFlow<AccountUser?> = mutableUser.asStateFlow()

    // This repository lives as long as AppContainer, so the listener lives for the process.
    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        mutableUser.value = firebaseAuth.currentUser?.toAccountUser()
    }

    init {
        auth?.addAuthStateListener(authListener)
    }

    override suspend fun signIn(idToken: String) {
        checkNotNull(auth) { "Firebase is not configured" }
        require(idToken.isNotBlank())
        val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        mutableUser.value = result.user?.toAccountUser()
    }

    override fun signOut() {
        auth?.signOut()
        mutableUser.value = null
    }
}

private fun FirebaseUser.toAccountUser() = AccountUser(
    uid = uid,
    name = displayName,
    email = email,
    avatarUrl = photoUrl?.toString(),
)
