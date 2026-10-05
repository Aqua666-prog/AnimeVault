package com.sergey.animevault.data.metadata

import com.sergey.animevault.data.online.OnlineEpisode
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun matchTenraiEpisode(
    episode: OnlineEpisode,
    metadata: List<TenraiEpisodeMetadata>,
): TenraiEpisodeMetadata? {
    val ordinal = episode.ordinal ?: return null
    val rounded = ordinal.roundToInt()
    if (rounded <= 0 || abs(ordinal - rounded.toDouble()) > 0.001) return null
    return metadata.firstOrNull { it.number == rounded }
}

internal fun formatTenraiAirDate(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val match = ISO_DATE.find(raw) ?: return raw.take(32)
    val (year, month, day) = match.destructured
    return "$day.$month.$year"
}

internal fun tenraiScheduleFilter(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.MONDAY -> "monday"
    Calendar.TUESDAY -> "tuesday"
    Calendar.WEDNESDAY -> "wednesday"
    Calendar.THURSDAY -> "thursday"
    Calendar.FRIDAY -> "friday"
    Calendar.SATURDAY -> "saturday"
    Calendar.SUNDAY -> "sunday"
    else -> "unknown"
}

internal fun TenraiHealthSnapshot.displayLabel(): String = when (state) {
    TenraiHealthState.HEALTHY -> "Tenrai работает"
    TenraiHealthState.DEGRADED -> if (servingStaleCache) {
        "Tenrai нестабилен · показан кэш"
    } else {
        "Tenrai работает нестабильно"
    }
    TenraiHealthState.RATE_LIMITED -> "Tenrai ограничил частоту запросов"
    TenraiHealthState.UNAVAILABLE -> if (servingStaleCache) {
        "Tenrai недоступен · показан кэш"
    } else {
        "Tenrai временно недоступен"
    }
}

private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")
