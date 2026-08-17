package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LibraryTitleRow
import org.junit.Test

class StorageSummaryTest {
    @Test fun sumsOnlyNonNegativeSizes() {
        val rows = listOf(
            LibraryTitleRow(1, "A", null, 0, 2, 1, null, 0, totalBytes = 1000, completedBytes = 400),
            LibraryTitleRow(2, "B", null, 0, 1, 1, null, 0, totalBytes = 2000, completedBytes = 2000),
            LibraryTitleRow(3, "C", null, 0, 1, 0, null, 0, totalBytes = -1, completedBytes = -1),
        )
        assertThat(summarizeStorage(rows)).isEqualTo(
            StorageSummary(totalBytes = 3000, reclaimableBytes = 2400, completedFiles = 2),
        )
    }
}
