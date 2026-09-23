package com.sergey.animevault.ui.home

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.online.OnlineLibraryEntry
import org.junit.Test

class LibraryInsightsTest {
    @Test fun aggregatesLocalAndOnlineSignals() {
        val titles = listOf(
            LibraryTitleRow(1, "A", null, 0, 10, 5, 1, 0, 1_000, 400, 3_600_000),
            LibraryTitleRow(2, "B", null, 0, 10, 10, 2, 0, 2_000, 2_000, 7_200_000),
        )
        val online = listOf(
            OnlineLibraryEntry("p", "P", "1", "One", lastOpenedAt = 1),
            OnlineLibraryEntry("p", "P", "2", "Two"),
        )
        assertThat(buildLibraryInsights(titles, online)).isEqualTo(
            LibraryInsights(
                watchedTimeMs = 10_800_000,
                completionPercent = 75,
                totalBytes = 3_000,
                reclaimableBytes = 2_400,
                onlineHistoryCount = 1,
            ),
        )
    }
}
