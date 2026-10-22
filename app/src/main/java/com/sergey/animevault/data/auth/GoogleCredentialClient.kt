package com.sergey.animevault.data.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.sergey.animevault.R

class GoogleCredentialClient(context: Context) {
    private val manager = CredentialManager.create(context.applicationContext)
    private val webClientId = context.getString(R.string.default_web_client_id)

    suspend fun getIdToken(activity: Activity): String {
        check(webClientId.isNotBlank()) { "Google Sign-In is not configured" }
        // Explicit Google button flow: includes accounts which have not used this app before.
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val result = manager.getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val credential = result.credential
        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Unexpected credential type" }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    suspend fun clearState() {
        manager.clearCredentialState(ClearCredentialStateRequest())
    }
}
