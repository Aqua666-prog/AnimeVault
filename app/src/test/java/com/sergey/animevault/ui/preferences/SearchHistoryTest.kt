package com.sergey.animevault.ui.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchHistoryTest {
    @Test
    fun `successful search moves normalized query to front without duplicates`() {
        val result = mergeSearchHistory(
            existing = listOf("Frieren", "Monogatari", "frieren"),
            query = "  Frieren   Beyond  ",
        )

        assertThat(result).containsExactly("Frieren Beyond", "Frieren", "Monogatari").inOrder()
    }

    @Test
    fun `same query replaces old casing and keeps most recent order`() {
        val result = mergeSearchHistory(
            existing = listOf("frieren", "Higurashi", "Umineko"),
            query = "FRIEREN",
        )

        assertThat(result).containsExactly("FRIEREN", "Higurashi", "Umineko").inOrder()
    }

    @Test
    fun `short fragments are not recorded`() {
        val original = listOf("Frieren", "Higurashi")
        assertThat(mergeSearchHistory(original, " f ")).containsExactlyElementsIn(original).inOrder()
    }

    @Test
    fun `history is capped`() {
        val original = (1..12).map { "Query $it" }
        val result = mergeSearchHistory(original, "Newest", maxItems = 8)

        assertThat(result).hasSize(8)
        assertThat(result.first()).isEqualTo("Newest")
    }
}
