package com.sergey.animevault.data.playback

import com.sergey.animevault.data.online.OnlineStream

/** User preference persisted per online title. */
data class OnlineStreamPreference(
    val translation: String? = null,
    val quality: Int? = null,
    val sourceName: String? = null,
)

/**
 * Compatibility facade for the current online UI.
 *
 * Ranking now lives in the provider-neutral PlaybackVariantResolver so the same fallback policy
 * can later choose between local files, multiple providers and TV/background playback.
 */
object OnlineStreamResolver {
    fun selectPreferred(
        streams: List<OnlineStream>,
        preference: OnlineStreamPreference,
    ): OnlineStream {
        require(streams.isNotEmpty()) { "Для серии нет доступных потоков" }

        if (
            preference.translation.isNullOrBlank() &&
            preference.quality == null &&
            preference.sourceName.isNullOrBlank()
        ) {
            return streams.first()
        }

        val variants = streams.associateByVariantKey()
        val selected = PlaybackVariantResolver.selectPreferred(
            variants = variants.keys.toList(),
            preference = PlaybackVariantPreference(
                translation = preference.translation,
                quality = preference.quality,
                sourceName = preference.sourceName,
                preferLocal = false,
            ),
        )
        return variants.getValue(selected)
    }

    fun selectFallback(
        streams: List<OnlineStream>,
        current: OnlineStream,
        failedStreamKeys: Set<String>,
        failure: PlaybackFailure? = null,
    ): OnlineStream? {
        val variants = streams.associateByVariantKey()
        val currentVariant = variants.keys.firstOrNull { it.key == failureKey(current) } ?: return null
        val selected = PlaybackVariantResolver.selectFallback(
            variants = variants.keys.toList(),
            current = currentVariant,
            failedVariantKeys = failedStreamKeys,
            failure = failure,
        ) ?: return null
        return variants[selected]
    }

    fun failureKey(stream: OnlineStream): String = OnlineStreamVariantKeys.keyOf(stream)

    private fun List<OnlineStream>.associateByVariantKey(): LinkedHashMap<PlaybackVariant, OnlineStream> {
        val result = linkedMapOf<PlaybackVariant, OnlineStream>()
        forEach { stream ->
            result[stream.toPlaybackVariant(
                episodeKey = RESOLVER_EPISODE_KEY,
                providerId = "online",
                providerName = stream.sourceName.orEmpty(),
            )] = stream
        }
        return result
    }

    private const val RESOLVER_EPISODE_KEY = "online-stream-resolver"
}
