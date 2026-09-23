package com.sergey.animevault.util

import java.util.Locale

fun formatDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

fun formatEpisodeNumber(value: Double): String = if (value % 1.0 == 0.0) {
    value.toLong().toString()
} else {
    value.toString().trimEnd('0').trimEnd('.')
}
