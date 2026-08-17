package com.sergey.animevault.ui.library

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.data.model.LibraryTitleRow
import org.junit.Test

class SmartCollectionTest {
    private fun row(
        id: Long,
        episodes: Long = 12,
        completed: Long = 0,
        watchedAt: Long? = null,
        links: Long = 0,
    ) = LibraryTitleRow(id, "Title $id", null, id, episodes, completed, watchedAt, links)

    private val titles = listOf(
        row(1, completed = 0),
        row(2, completed = 3, watchedAt = 100),
        row(3, completed = 12, watchedAt = 200),
        row(4, completed = 0, links = 1),
    )

    @Test fun inProgressExcludesUntouchedAndCompleted() {
        assertThat(applySmartCollection(titles, SmartCollection.InProgress).map { it.id })
            .containsExactly(2L)
    }

    @Test fun unwatchedRequiresNoProgressHistory() {
        assertThat(applySmartCollection(titles, SmartCollection.Unwatched).map { it.id })
            .containsExactly(1L, 4L)
    }

    @Test fun completedRequiresEveryEpisode() {
        assertThat(applySmartCollection(titles, SmartCollection.Completed).map { it.id })
            .containsExactly(3L)
    }

    @Test fun linkedOnlineUsesLinkCount() {
        assertThat(applySmartCollection(titles, SmartCollection.LinkedOnline).map { it.id })
            .containsExactly(4L)
    }
}
