package com.sergey.animevault.ui.player

import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import java.util.Locale

/**
 * Chooses the closest stream to the profile explicitly selected by the user on
 * a previous episode. With an empty profile the provider's original order is
 * preserved, so this feature does not silently change legacy behaviour.
 */
internal fun selectPreferredOnlineStream(
    streams: List<OnlineStream>,
    translation: String?,
    quality: Int?,
    sourceName: String?,
): OnlineStream {
    require(streams.isNotEmpty()) { "Для серии нет доступных потоков" }
    val preferredTranslation = translation?.trim()?.lowercase(Locale.ROOT)
    val preferredSource = sourceName?.trim()?.lowercase(Locale.ROOT)
    if (preferredTranslation.isNullOrBlank() && quality == null && preferredSource.isNullOrBlank()) {
        return streams.first()
    }
    return streams.maxByOrNull { stream ->
        var score = 0
        val streamTranslation = stream.translation?.trim()?.lowercase(Locale.ROOT)
        val streamSource = stream.sourceName?.trim()?.lowercase(Locale.ROOT)
        if (!preferredTranslation.isNullOrBlank() && streamTranslation == preferredTranslation) score += 10_000
        if (quality != null && stream.quality == quality) score += 1_500
        if (!preferredSource.isNullOrBlank() && streamSource == preferredSource) score += 700
        if (stream.type != OnlineStreamType.EMBED) score += 120
        score += (stream.quality ?: 0).coerceAtMost(2160) / 10
        score
    } ?: streams.first()
}
