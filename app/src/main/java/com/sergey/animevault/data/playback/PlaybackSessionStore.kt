package com.sergey.animevault.data.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small state holder shared by offline and online players.
 *
 * Media3 is deliberately not the source of truth for the playback session. The player may be
 * recreated when the Activity stops, when stream headers change, or when another variant is
 * selected. The session survives those recreations in the ViewModel and receives Media3 events
 * through [PlaybackSessionEvent].
 */
class PlaybackSessionStore(
    initial: PlaybackSession = PlaybackSession(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PlaybackSession> = _state.asStateFlow()

    @Synchronized
    fun dispatch(event: PlaybackSessionEvent): PlaybackSession {
        val updated = PlaybackSessionReducer.reduce(_state.value, event)
        _state.value = updated
        return updated
    }

    fun reset(): PlaybackSession = dispatch(PlaybackSessionEvent.Reset)
}
