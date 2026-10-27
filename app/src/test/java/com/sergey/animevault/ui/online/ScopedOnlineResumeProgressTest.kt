package com.sergey.animevault.ui.online

import com.sergey.animevault.data.online.EpisodeIdentity
import com.sergey.animevault.data.online.OnlineWatchProgress
import org.junit.Assert.*
import org.junit.Test

class ScopedOnlineResumeProgressTest {
    @Test fun oldUnifiedHistoryIsPreservedButNotTransferredWithoutSourceProof() {
        val legacy = OnlineWatchProgress(positionMs = 755000, durationMs = 1400000)
        val source = EpisodeIdentity("one", "release", "ep")
        assertEquals(0L, scopedOnlineResumeProgress("all", source, legacy, OnlineWatchProgress()).positionMs)
        assertEquals(755000L, legacy.positionMs)
        assertEquals(3000L, scopedOnlineResumeProgress("all", source, legacy, OnlineWatchProgress(positionMs = 3000)).positionMs)
    }
    @Test fun directProviderHistoryStillResumesAfterUpdateFrom72Or73() {
        val legacy = OnlineWatchProgress(positionMs = 755000)
        assertEquals(755000L, scopedOnlineResumeProgress("one", EpisodeIdentity("one", "release", "ep"), legacy, null).positionMs)
        assertEquals(755000L, scopedOnlineResumeProgress("one", null, legacy, null).positionMs)
    }
}
