package com.sergey.animevault.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sergey.animevault.R
import com.sergey.animevault.data.download.DownloadEntry
import com.sergey.animevault.data.download.DownloadMediaSource
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineWatchProgress
import com.sergey.animevault.data.playback.PlaybackSessionStore
import com.sergey.animevault.ui.online.OnlinePlaybackBundle

/** Builds an offline playback bundle while preserving the online episode identity. */
@Composable
internal fun DownloadedPlayerRoute(
    entry: DownloadEntry,
    source: DownloadMediaSource,
    initialProgress: OnlineWatchProgress,
    onBack: () -> Unit,
    onSaveProgress: (Long, Long, Boolean) -> Unit,
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val sessionStore = remember(entry.id) { PlaybackSessionStore() }
    val offlineSourceName = stringResource(R.string.download_offline_source)
    val playbackSession by sessionStore.state.collectAsStateWithLifecycle()
    val stream = remember(entry.id, source.url, offlineSourceName) {
        OnlineStream(
            id = "download:${entry.id}",
            quality = entry.quality,
            url = source.url,
            type = entry.streamType,
            headers = source.headers,
            translation = entry.translation,
            sourceName = entry.sourceName ?: offlineSourceName,
            providerId = entry.providerId,
            providerName = entry.providerName,
            offlineCacheId = entry.id,
        )
    }
    val episode = remember(entry.id) {
        OnlineEpisode(
            providerId = entry.providerId,
            id = entry.episodeId,
            releaseId = entry.releaseId,
            ordinal = entry.episodeOrdinal,
            name = entry.episodeName,
            previewUrl = null,
            durationMs = 0L,
            sortOrder = entry.episodeOrdinal,
            streams = listOf(stream),
        )
    }
    val playback = OnlinePlaybackBundle(
        providerId = entry.providerId,
        providerName = entry.providerName,
        releaseId = entry.releaseId,
        releaseName = entry.releaseName,
        episode = episode,
        episodes = listOf(episode),
        progress = initialProgress,
        episodeProgress = mapOf(episode.id to initialProgress),
        nextEpisodeId = null,
    )
    OnlineVideoPlayer(
        playback = playback,
        playbackSession = playbackSession,
        onPlaybackSessionEvent = sessionStore::dispatch,
        onSaveProgress = onSaveProgress,
        onSelectStream = {},
        onBack = onBack,
        onPlayEpisode = {},
        isInPictureInPictureMode = isInPictureInPictureMode,
        onEnterPictureInPicture = onEnterPictureInPicture,
    )
}
