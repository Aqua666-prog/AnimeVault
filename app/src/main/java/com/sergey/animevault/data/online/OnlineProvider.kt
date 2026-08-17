package com.sergey.animevault.data.online

interface OnlineProvider {
    val descriptor: OnlineProviderDescriptor

    suspend fun getCatalog(
        page: Int,
        limit: Int = 24,
        search: String = "",
    ): OnlineCatalogPage

    suspend fun getRelease(id: String): OnlineReleaseDetails

    suspend fun resolveStreams(
        releaseId: String,
        episode: OnlineEpisode,
    ): List<OnlineStream> = episode.streams
}

interface AccountOnlineProvider : OnlineProvider {
    fun accountState(): ProviderAccountState

    suspend fun signIn(login: String, password: String): ProviderLoginResult

    fun signOut()
}

interface TokenOnlineProvider : OnlineProvider {
    fun accountState(): ProviderAccountState

    fun setToken(token: String)

    fun signOut()
}

class OnlineSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
