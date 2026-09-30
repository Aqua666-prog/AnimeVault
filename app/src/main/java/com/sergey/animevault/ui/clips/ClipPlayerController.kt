package com.sergey.animevault.ui.clips

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

data class ClipPlaybackRequest(
    val url: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
)

/**
 * Two-player handoff: one player renders the current clip while the second one
 * prepares the next item. When the user swipes, the prepared player becomes
 * active and the old player is recycled for the following preload.
 */
class ClipPlayerController(context: Context) {
    private val appContext = context.applicationContext
    private var active = newPlayer()
    private var standby = newPlayer()
    private var activeRequest: ClipPlaybackRequest? = null
    private var standbyRequest: ClipPlaybackRequest? = null

    val currentPlayer: ExoPlayer
        get() = active

    fun play(request: ClipPlaybackRequest?, muted: Boolean): ExoPlayer {
        if (request == null) {
            active.pause()
            active.stop()
            active.clearMediaItems()
            activeRequest = null
            active.volume = if (muted) 0f else 1f
            return active
        }

        if (request == activeRequest) {
            active.volume = if (muted) 0f else 1f
            active.playWhenReady = true
            active.play()
            return active
        }

        if (request == standbyRequest) {
            val oldActive = active
            active.pause()
            active = standby
            standby = oldActive
            activeRequest = request
            standbyRequest = null
            active.volume = if (muted) 0f else 1f
            active.playWhenReady = true
            active.play()
            standby.stop()
            standby.clearMediaItems()
            return active
        }

        active.setMediaItem(request.toMediaItem())
        active.prepare()
        activeRequest = request
        active.volume = if (muted) 0f else 1f
        active.playWhenReady = true
        return active
    }

    fun preload(request: ClipPlaybackRequest?) {
        if (request == null || request == activeRequest || request == standbyRequest) return
        standby.stop()
        standby.clearMediaItems()
        standby.setMediaItem(request.toMediaItem())
        standby.volume = 0f
        standby.playWhenReady = false
        standby.prepare()
        standbyRequest = request
    }

    fun setMuted(muted: Boolean) {
        active.volume = if (muted) 0f else 1f
    }

    fun pause() {
        active.pause()
    }

    fun resume() {
        if (active.mediaItemCount > 0) active.play()
    }

    fun release() {
        active.release()
        standby.release()
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
    }
}

private fun ClipPlaybackRequest.toMediaItem(): MediaItem {
    val safeStart = startPositionMs.coerceAtLeast(0L)
    val safeEnd = endPositionMs.coerceAtLeast(safeStart + 1_000L)
    return MediaItem.Builder()
        .setUri(url)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(safeStart)
                .setEndPositionMs(safeEnd)
                .build(),
        )
        .build()
}
