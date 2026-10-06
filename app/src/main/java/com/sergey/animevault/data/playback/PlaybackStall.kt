package com.sergey.animevault.data.playback

/**
 * Small player-agnostic watchdog used by online playback.
 *
 * Media3 can remain in READY/BUFFERING without surfacing an exception when a CDN stalls.
 * The detector treats real timeline movement as progress and asks the recovery layer to
 * intervene only after a sustained period with no movement while playback is expected.
 */
class PlaybackStallDetector(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val minimumProgressMs: Long = DEFAULT_MINIMUM_PROGRESS_MS,
) {
    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        require(minimumProgressMs >= 0L) { "minimumProgressMs must not be negative" }
    }

    private var lastProgressPositionMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var signalled = false

    /**
     * Returns true once when playback has not advanced for [timeoutMs].
     * Calling with expectsProgress=false resets the idle timer, so pause/seek/UI work
     * never looks like a network stall.
     */
    fun observe(
        nowMs: Long,
        positionMs: Long,
        expectsProgress: Boolean,
    ): Boolean {
        val safeNow = nowMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceAtLeast(0L)

        if (!expectsProgress) {
            lastProgressPositionMs = safePosition
            lastProgressAtMs = safeNow
            signalled = false
            return false
        }

        val previousPosition = lastProgressPositionMs
        val previousAt = lastProgressAtMs
        if (previousPosition == null || previousAt == null) {
            lastProgressPositionMs = safePosition
            lastProgressAtMs = safeNow
            return false
        }

        val advanced = safePosition - previousPosition >= minimumProgressMs
        val jumpedBack = safePosition + minimumProgressMs < previousPosition
        if (advanced || jumpedBack) {
            lastProgressPositionMs = safePosition
            lastProgressAtMs = safeNow
            signalled = false
            return false
        }

        if (!signalled && safeNow - previousAt >= timeoutMs) {
            signalled = true
            return true
        }
        return false
    }

    fun reset(nowMs: Long = 0L, positionMs: Long = 0L) {
        lastProgressPositionMs = positionMs.coerceAtLeast(0L)
        lastProgressAtMs = nowMs.coerceAtLeast(0L)
        signalled = false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val DEFAULT_MINIMUM_PROGRESS_MS = 500L
    }
}
