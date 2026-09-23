package com.sergey.animevault.ui.player

import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.playback.OnlineStreamPreference
import com.sergey.animevault.data.playback.OnlineStreamResolver

/**
 * Compatibility wrapper kept near the Compose player while ranking lives in
 * the playback core and can be reused by future TV/background players.
 */
internal fun selectPreferredOnlineStream(
    streams: List<OnlineStream>,
    translation: String?,
    quality: Int?,
    sourceName: String?,
): OnlineStream = OnlineStreamResolver.selectPreferred(
    streams = streams,
    preference = OnlineStreamPreference(
        translation = translation,
        quality = quality,
        sourceName = sourceName,
    ),
)
