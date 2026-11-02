package com.sergey.animevault.data.community

import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * AnimeVault identifiers, generated locally from catalogue metadata.
 *
 * No video provider token, HTTP request, provider slug or release URL is involved.
 * Identical MAL/Shikimori IDs share a topic; otherwise the same AniList ID does.
 * Title fingerprints are intentionally conservative fallbacks, not verified crosswalks.
 */
fun appCommunityIdentity(release: OnlineReleaseDetails): CommunityIdentity {
    val ids = release.externalIds
    val mal = (ids.malId ?: ids.shikimoriId)?.takeIf { it in 1..2_147_483_647L }
    val anilist = ids.anilistId?.takeIf { it in 1..2_147_483_647L }
    val label = (release.englishName?.takeIf { it.isNotBlank() } ?: release.name)
    val fallback = listOf(normalizeTopicText(label), release.year?.toString().orEmpty())
        .joinToString("|")
    val key = when {
        mal != null -> "av1:mal:$mal"
        anilist != null -> "av1:anilist:$anilist"
        else -> "av1:title:${sha256(fallback)}"
    }
    return CommunityIdentity(appKey = key, appTitle = release.name.trim().take(300))
}

/** Numbered episodes are source-independent. Unnumbered specials stay separate by name. */
fun appCommunityEpisodeIdentity(topic: CommunityIdentity, episode: OnlineEpisode): CommunityIdentity {
    val ordinal = episode.ordinal?.takeIf { it.isFinite() && it > 0.0 && it <= 10_000.0 }
    val key: String
    val number: Int?
    if (ordinal != null && ordinal % 1.0 == 0.0) {
        number = ordinal.toInt()
        key = "regular:$number"
    } else if (ordinal != null) {
        number = null
        key = "fractional:${BigDecimal.valueOf(ordinal).stripTrailingZeros().toPlainString()}"
    } else {
        number = null
        // Distinct unknown episodes must never silently collapse into one comment thread.
        val fallback = normalizeTopicText(episode.name.orEmpty()).ifBlank { episode.id }
        key = "special:${sha256(fallback).take(24)}"
    }
    return topic.copy(episodeKey = key, episodeNumber = number)
}

private fun normalizeTopicText(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
