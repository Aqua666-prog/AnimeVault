package com.sergey.animevault.ui.player

import android.os.Build
import android.os.SystemClock
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/** Measures prepare-to-first-frame when system tracing is active. All calls are on the player thread. */
internal class PlaybackFirstFrameTrace(private val source: String) {
    private val section = "AnimeVault.prepare.$source"
    private val cookie = nextCookie.incrementAndGet()
    private var startedAt = 0L

    fun start() {
        end()
        if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
            startedAt = SystemClock.elapsedRealtime()
            Trace.beginAsyncSection(section, cookie)
        }
    }

    fun rendered() {
        if (Build.VERSION.SDK_INT >= 29 && startedAt != 0L) {
            Trace.setCounter("AnimeVault.firstFrameMs.$source", SystemClock.elapsedRealtime() - startedAt)
        }
        end()
    }

    fun end() {
        if (Build.VERSION.SDK_INT >= 29 && startedAt != 0L) {
            Trace.endAsyncSection(section, cookie)
            startedAt = 0L
        }
    }

    private companion object {
        val nextCookie = AtomicInteger()
    }
}
