package com.sergey.animevault.data.playback

/** Human-readable summary of how much of an episode's playback plan is still usable. */
data class PlaybackExhaustionReport(
    val totalVariants: Int,
    val failedVariants: Int,
    val coolingDownVariants: Int,
    val remainingVariants: Int,
) {
    val exhausted: Boolean get() = totalVariants > 0 && remainingVariants == 0
    val temporarilyExhausted: Boolean get() = exhausted && coolingDownVariants > 0

    fun userMessage(defaultMessage: String): String {
        if (!exhausted) return defaultMessage
        return if (temporarilyExhausted) {
            "Все доступные зеркала этой серии временно недоступны. AnimeVault уже попробовал остальные варианты; повторите через несколько секунд."
        } else {
            "Все доступные потоки этой серии исчерпаны. Попробуйте другую озвучку, источник или повторите позже."
        }
    }
}

object PlaybackSourceExhaustion {
    fun inspect(
        variants: List<PlaybackVariant>,
        failedVariantKeys: Set<String>,
        blockedHostFamilies: Set<String>,
    ): PlaybackExhaustionReport {
        val normalizedBlocked = blockedHostFamilies.map { it.lowercase() }.toSet()
        var failed = 0
        var coolingDown = 0
        var remaining = 0

        variants.forEach { variant ->
            when {
                variant.key in failedVariantKeys -> failed++
                variant.normalizedHostFamily()?.let { it in normalizedBlocked } == true -> coolingDown++
                variant.uri.isNotBlank() -> remaining++
            }
        }
        return PlaybackExhaustionReport(
            totalVariants = variants.size,
            failedVariants = failed,
            coolingDownVariants = coolingDown,
            remainingVariants = remaining,
        )
    }
}
