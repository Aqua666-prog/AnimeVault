package com.sergey.animevault.data.download

import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType

/** Applied to every episode in a season; persisted in each WorkManager request. */
enum class SeasonQualityPolicy {
    /** Never deliberately select a known resolution higher than requested. */
    LOWER,
    /** Prefer the requested resolution, then the closest available resolution. */
    ANY,
    /** Require a verifiable match (unknown-quality direct playlists are rejected). */
    STRICT,
}

/** Stable choice made before scheduling a season episode. */
fun chooseSeasonDownloadStream(
    streams: List<OnlineStream>,
    preferredTranslationKey: String?,
    preferredQuality: Int?,
    qualityPolicy: SeasonQualityPolicy,
): OnlineStream? {
    val playable = streams.filter(OnlineStream::isDownloadable).let { downloadable ->
        if (preferredTranslationKey.isNullOrBlank()) downloadable
        else downloadable.filter { it.matchesTranslationPreference(preferredTranslationKey) }
    }
    if (playable.isEmpty()) return null
    if (preferredQuality == null) {
        return chooseDownloadStream(playable, preferredTranslationKey, null)
    }

    val exact = playable.filter { it.quality == preferredQuality }
    if (exact.isNotEmpty()) return chooseDownloadStream(exact, preferredTranslationKey, preferredQuality)

    // A master HLS advertises renditions only after loading the playlist.
    val master = playable.filter { it.quality == null && it.type == OnlineStreamType.HLS }
    if (master.isNotEmpty()) return chooseDownloadStream(master, preferredTranslationKey, preferredQuality)

    val candidates = playable.filter { it.quality != null }.let { known ->
        when (qualityPolicy) {
            SeasonQualityPolicy.STRICT -> emptyList()
            SeasonQualityPolicy.LOWER -> known.filter { (it.quality ?: Int.MAX_VALUE) <= preferredQuality }
            SeasonQualityPolicy.ANY -> known
        }
    }
    if (candidates.isEmpty()) return null
    val best = when (qualityPolicy) {
        SeasonQualityPolicy.LOWER -> candidates.maxOf { it.quality ?: 0 }
        SeasonQualityPolicy.ANY -> candidates.minWithOrNull(
            compareBy<OnlineStream> { kotlin.math.abs((it.quality ?: 0) - preferredQuality) }
                .thenBy { if ((it.quality ?: 0) <= preferredQuality) 0 else 1 },
        )?.quality ?: return null
        SeasonQualityPolicy.STRICT -> return null
    }
    return chooseDownloadStream(candidates.filter { it.quality == best }, preferredTranslationKey, best)
}

/** Deterministic, duplicate-free season ordering even for irregular episode ordinals. */
fun orderedSeasonEpisodeIds(selectedIds: Collection<String>, episodeOrder: List<String>): List<String> {
    val wanted = selectedIds.toSet()
    return episodeOrder.distinct().filter { it in wanted }
}
