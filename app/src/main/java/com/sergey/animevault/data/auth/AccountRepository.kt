package com.sergey.animevault.data.auth

import kotlinx.coroutines.flow.StateFlow

data class AccountUser(
    val uid: String,
    val name: String?,
    val email: String?,
    val avatarUrl: String?,
)

interface AccountRepository {
    val configured: Boolean
    val user: StateFlow<AccountUser?>
    suspend fun signIn(idToken: String)
    fun signOut()
}
