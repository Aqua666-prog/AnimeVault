package com.sergey.animevault.ui.title

enum class UnifiedTitleOrigin {
    LOCAL,
    ONLINE,
    HYBRID,
}

data class UnifiedTitleSourceUi(
    val providerId: String,
    val releaseId: String,
    val name: String,
    val isCurrent: Boolean = false,
)

data class UnifiedTitleUiModel(
    val title: String,
    val secondaryTitle: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val season: String? = null,
    val totalEpisodes: Int = 0,
    val completedEpisodes: Int = 0,
    val inProgressEpisodes: Int = 0,
    val localTitleId: Long? = null,
    val localTitleName: String? = null,
    val localEpisodeCount: Int = 0,
    val onlineSources: List<UnifiedTitleSourceUi> = emptyList(),
    val isOngoing: Boolean = false,
    val scoreLabel: String? = null,
) {
    val origin: UnifiedTitleOrigin
        get() = unifiedTitleOrigin(localTitleId != null, onlineSources.size)
}

fun unifiedTitleOrigin(hasLocal: Boolean, onlineSourceCount: Int): UnifiedTitleOrigin = when {
    hasLocal && onlineSourceCount > 0 -> UnifiedTitleOrigin.HYBRID
    hasLocal -> UnifiedTitleOrigin.LOCAL
    else -> UnifiedTitleOrigin.ONLINE
}
