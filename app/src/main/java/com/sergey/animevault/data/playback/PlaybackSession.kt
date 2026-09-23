package com.sergey.animevault.data.playback

/** UI-independent lifecycle state for the Playback Engine 2.0. */
enum class PlaybackEnginePhase {
    IDLE,
    PREPARING,
    READY,
    BUFFERING,
    PLAYING,
    PAUSED,
    SWITCHING_VARIANT,
    ENDED,
    ERROR,
}

data class PlaybackSession(
    val episodeKey: String? = null,
    val variantKey: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val playWhenReady: Boolean = false,
    val phase: PlaybackEnginePhase = PlaybackEnginePhase.IDLE,
    val failure: PlaybackFailure? = null,
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f
        else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

sealed interface PlaybackSessionEvent {
    data class Prepare(
        val episodeKey: String,
        val variantKey: String,
        val positionMs: Long,
        val durationMs: Long = 0L,
        val speed: Float = 1f,
        val playWhenReady: Boolean = true,
    ) : PlaybackSessionEvent

    data object Ready : PlaybackSessionEvent
    data object Buffering : PlaybackSessionEvent
    data object Playing : PlaybackSessionEvent
    data object Paused : PlaybackSessionEvent

    data class Timeline(
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
    ) : PlaybackSessionEvent

    data class Seek(val positionMs: Long) : PlaybackSessionEvent
    data class Speed(val speed: Float) : PlaybackSessionEvent
    data class PlayWhenReady(val value: Boolean) : PlaybackSessionEvent
    data class SwitchVariant(val variantKey: String) : PlaybackSessionEvent
    data class Failed(val failure: PlaybackFailure) : PlaybackSessionEvent
    data object Ended : PlaybackSessionEvent
    data object Reset : PlaybackSessionEvent
}

/** Pure reducer so Compose, TV and tests can share exactly the same playback state semantics. */
object PlaybackSessionReducer {
    fun reduce(
        state: PlaybackSession,
        event: PlaybackSessionEvent,
    ): PlaybackSession = when (event) {
        is PlaybackSessionEvent.Prepare -> PlaybackSession(
            episodeKey = event.episodeKey,
            variantKey = event.variantKey,
            positionMs = event.positionMs.coerceAtLeast(0L),
            durationMs = event.durationMs.coerceAtLeast(0L),
            bufferedPositionMs = event.positionMs.coerceAtLeast(0L),
            speed = event.speed.coerceIn(MIN_SPEED, MAX_SPEED),
            playWhenReady = event.playWhenReady,
            phase = PlaybackEnginePhase.PREPARING,
        )

        PlaybackSessionEvent.Ready -> state.copy(
            phase = PlaybackEnginePhase.READY,
            failure = null,
        )

        PlaybackSessionEvent.Buffering -> state.copy(phase = PlaybackEnginePhase.BUFFERING)
        PlaybackSessionEvent.Playing -> state.copy(
            phase = PlaybackEnginePhase.PLAYING,
            playWhenReady = true,
            failure = null,
        )

        PlaybackSessionEvent.Paused -> state.copy(
            phase = PlaybackEnginePhase.PAUSED,
            playWhenReady = false,
        )

        is PlaybackSessionEvent.Timeline -> state.copy(
            positionMs = clampPosition(event.positionMs, event.durationMs),
            durationMs = event.durationMs.coerceAtLeast(0L),
            bufferedPositionMs = event.bufferedPositionMs.coerceAtLeast(0L),
        )

        is PlaybackSessionEvent.Seek -> state.copy(
            positionMs = clampPosition(event.positionMs, state.durationMs),
        )

        is PlaybackSessionEvent.Speed -> state.copy(speed = event.speed.coerceIn(MIN_SPEED, MAX_SPEED))
        is PlaybackSessionEvent.PlayWhenReady -> state.copy(playWhenReady = event.value)
        is PlaybackSessionEvent.SwitchVariant -> state.copy(
            variantKey = event.variantKey,
            phase = PlaybackEnginePhase.SWITCHING_VARIANT,
            failure = null,
        )

        is PlaybackSessionEvent.Failed -> state.copy(
            phase = PlaybackEnginePhase.ERROR,
            failure = event.failure,
        )

        PlaybackSessionEvent.Ended -> state.copy(
            positionMs = state.durationMs.takeIf { it > 0L } ?: state.positionMs,
            phase = PlaybackEnginePhase.ENDED,
            playWhenReady = false,
            failure = null,
        )

        PlaybackSessionEvent.Reset -> PlaybackSession()
    }

    private fun clampPosition(positionMs: Long, durationMs: Long): Long {
        val safe = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) safe.coerceAtMost(durationMs) else safe
    }

    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 4f
}
