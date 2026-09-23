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


internal fun OnlineProviderDescriptor.requireCatalogCapability(search: String) {
    val query = search.trim()
    if (query.isBlank() && !capabilities.catalog) {
        throw OnlineSourceException("$name не поддерживает просмотр общего каталога")
    }
    if (query.isNotBlank() && !capabilities.search) {
        throw OnlineSourceException("$name не поддерживает поиск")
    }
    if (query.isNotBlank() && query.length < minimumSearchLength.coerceAtLeast(1)) {
        throw OnlineSourceException("$name начинает поиск с ${minimumSearchLength.coerceAtLeast(1)} символов")
    }
}

internal fun OnlineProviderDescriptor.requireReleaseCapability() {
    if (!capabilities.releaseDetails) {
        throw OnlineSourceException("$name не поддерживает карточку тайтла")
    }
}

internal fun OnlineProviderDescriptor.requireStreamCapability() {
    if (!capabilities.streams) {
        throw OnlineSourceException("$name не предоставляет видеопотоки")
    }
}
