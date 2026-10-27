package com.sergey.animevault.data.playback

enum class SourceTransitionPhase { IDLE, SNAPSHOTTING, DISCOVERING, VALIDATING, AWAITING_CONFIRMATION, PREPARING, RESTORING, COMPLETED, ROLLING_BACK, FAILED, CANCELLED }
data class SourceTransitionSnapshot(
    val variantKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val playWhenReady: Boolean,
    val speed: Float,
)
data class PlaybackSourceTransition(
    val token: Long = 0,
    val phase: SourceTransitionPhase = SourceTransitionPhase.IDLE,
    val targetKey: String? = null,
    val snapshot: SourceTransitionSnapshot? = null,
    val attemptedKeys: Set<String> = emptySet(),
    val reason: String? = null,
) {
    val busy get() = phase in setOf(SourceTransitionPhase.SNAPSHOTTING, SourceTransitionPhase.DISCOVERING,
        SourceTransitionPhase.VALIDATING, SourceTransitionPhase.AWAITING_CONFIRMATION, SourceTransitionPhase.PREPARING, SourceTransitionPhase.RESTORING)
}

sealed interface SourceTransitionEvent {
    data class Begin(val targetKey: String, val snapshot: SourceTransitionSnapshot, val manual: Boolean) : SourceTransitionEvent
    data class Advance(val token: Long, val phase: SourceTransitionPhase) : SourceTransitionEvent
    data class Fail(val token: Long, val reason: String) : SourceTransitionEvent
    data class Cancel(val token: Long) : SourceTransitionEvent
}

/** Part of PlaybackSessionReducer, not a second player/session store. */
internal fun reduceSourceTransition(state: PlaybackSession, event: SourceTransitionEvent): PlaybackSession {
    val operation = state.sourceTransition
    return when (event) {
        is SourceTransitionEvent.Begin -> {
            val attempted = if (event.manual) emptySet() else operation.attemptedKeys
            if (operation.busy || event.targetKey == event.snapshot.variantKey || event.targetKey in attempted || attempted.size >= 3) state
            else state.copy(sourceTransition = PlaybackSourceTransition(operation.token + 1,
                SourceTransitionPhase.SNAPSHOTTING, event.targetKey, event.snapshot, attempted + event.targetKey))
        }
        is SourceTransitionEvent.Advance -> {
            val allowed = when (operation.phase) {
                SourceTransitionPhase.SNAPSHOTTING -> setOf(SourceTransitionPhase.DISCOVERING)
                SourceTransitionPhase.DISCOVERING -> setOf(SourceTransitionPhase.VALIDATING)
                SourceTransitionPhase.VALIDATING -> setOf(SourceTransitionPhase.AWAITING_CONFIRMATION, SourceTransitionPhase.PREPARING)
                SourceTransitionPhase.AWAITING_CONFIRMATION -> setOf(SourceTransitionPhase.PREPARING)
                SourceTransitionPhase.PREPARING -> setOf(SourceTransitionPhase.RESTORING)
                SourceTransitionPhase.RESTORING -> setOf(SourceTransitionPhase.COMPLETED)
                else -> emptySet()
            }
            if (event.token != operation.token || event.phase !in allowed) state
            else state.copy(sourceTransition = operation.copy(phase = event.phase))
        }
        is SourceTransitionEvent.Fail -> if (event.token == operation.token && operation.busy)
            state.copy(sourceTransition = operation.copy(phase = SourceTransitionPhase.FAILED, reason = event.reason)) else state
        is SourceTransitionEvent.Cancel -> {
            if (event.token != operation.token) state else operation.snapshot?.let { snapshot ->
                state.copy(variantKey = snapshot.variantKey, positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs, speed = snapshot.speed, playWhenReady = false,
                    phase = PlaybackEnginePhase.PAUSED,
                    sourceTransition = operation.copy(token = operation.token + 1, phase = SourceTransitionPhase.CANCELLED))
            } ?: state
        }
    }
}

fun safeSourceRestorePosition(positionMs: Long, durationMs: Long): Long = positionMs.coerceIn(0L,
    if (durationMs > 0) (durationMs - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE)
