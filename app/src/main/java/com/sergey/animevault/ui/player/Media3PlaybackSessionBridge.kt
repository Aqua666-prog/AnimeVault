package com.sergey.animevault.ui.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.sergey.animevault.data.playback.PlaybackEnginePhase
import com.sergey.animevault.data.playback.PlaybackFailureClassifier
import com.sergey.animevault.data.playback.PlaybackSession
import com.sergey.animevault.data.playback.PlaybackSessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Adapts Media3 callbacks to the UI-independent PlaybackSession state machine.
 *
 * A bridge instance is cheap and tied to one attached Player. The actual session is owned by the
 * ViewModel, so recreating ExoPlayer or switching a stream does not erase playback state.
 */
internal class Media3PlaybackSessionBridge(
    private val coroutineScope: CoroutineScope,
    private val currentSession: () -> PlaybackSession,
    private val dispatch: (PlaybackSessionEvent) -> Unit,
    private val fallbackDurationMs: () -> Long = { 0L },
) {
    private var attachedPlayer: Player? = null
    private var timelineJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> dispatch(PlaybackSessionEvent.Buffering)
                Player.STATE_READY -> {
                    dispatch(PlaybackSessionEvent.Ready)
                    dispatchPlayingState(attachedPlayer)
                }
                Player.STATE_ENDED -> dispatch(PlaybackSessionEvent.Ended)
                Player.STATE_IDLE -> Unit
            }
            publishTimeline(attachedPlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                dispatch(PlaybackSessionEvent.Playing)
            } else if (shouldMarkPaused(currentSession())) {
                dispatch(PlaybackSessionEvent.Paused)
            }
            publishTimeline(attachedPlayer)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            dispatch(PlaybackSessionEvent.PlayWhenReady(playWhenReady))
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            dispatch(PlaybackSessionEvent.Speed(playbackParameters.speed))
        }

        override fun onPlayerError(error: PlaybackException) {
            publishTimeline(attachedPlayer)
            dispatch(PlaybackSessionEvent.Failed(PlaybackFailureClassifier.classify(error)))
        }

        override fun onEvents(player: Player, events: Player.Events) {
            publishTimeline(player)
        }
    }

    fun attach(player: Player) {
        if (attachedPlayer === player) return
        detach()
        attachedPlayer = player
        player.addListener(listener)
        publishTimeline(player)
        dispatchInitialState(player)
        timelineJob = coroutineScope.launch {
            while (isActive) {
                delay(TIMELINE_POLL_MS)
                publishTimeline(attachedPlayer)
            }
        }
    }

    fun detach() {
        val player = attachedPlayer
        if (player != null) {
            publishTimeline(player)
            player.removeListener(listener)
        }
        timelineJob?.cancel()
        timelineJob = null
        attachedPlayer = null
    }

    private fun dispatchInitialState(player: Player) {
        dispatch(PlaybackSessionEvent.Speed(player.playbackParameters.speed))
        dispatch(PlaybackSessionEvent.PlayWhenReady(player.playWhenReady))
        when {
            player.playbackState == Player.STATE_ENDED -> dispatch(PlaybackSessionEvent.Ended)
            player.isPlaying -> dispatch(PlaybackSessionEvent.Playing)
            player.playbackState == Player.STATE_BUFFERING -> dispatch(PlaybackSessionEvent.Buffering)
            player.playbackState == Player.STATE_READY -> dispatch(PlaybackSessionEvent.Ready)
        }
    }

    private fun dispatchPlayingState(player: Player?) {
        if (player?.isPlaying == true) {
            dispatch(PlaybackSessionEvent.Playing)
        } else if (shouldMarkPaused(currentSession())) {
            dispatch(PlaybackSessionEvent.Paused)
        }
    }

    private fun shouldMarkPaused(session: PlaybackSession): Boolean = when (session.phase) {
        PlaybackEnginePhase.IDLE,
        PlaybackEnginePhase.PREPARING,
        PlaybackEnginePhase.BUFFERING,
        PlaybackEnginePhase.SWITCHING_VARIANT,
        PlaybackEnginePhase.ENDED,
        PlaybackEnginePhase.ERROR -> false
        PlaybackEnginePhase.READY,
        PlaybackEnginePhase.PLAYING,
        PlaybackEnginePhase.PAUSED -> true
    }

    private fun publishTimeline(player: Player?) {
        player ?: return
        dispatch(
            PlaybackSessionEvent.Timeline(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.validSessionDuration(fallbackDurationMs()),
                bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            ),
        )
    }

    private fun Player.validSessionDuration(fallbackMs: Long): Long =
        duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: fallbackMs.coerceAtLeast(0L)

    companion object {
        private const val TIMELINE_POLL_MS = 500L
    }
}
