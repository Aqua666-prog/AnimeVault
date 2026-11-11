package com.sergey.animevault.ui.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationIndicesTest {
    @Test fun keepsValidModelOrderAndRemovesDuplicates() {
        assertEquals(listOf(1, 0), recommendationIndices(" [2, 2, 1] ", 3))
    }
    @Test fun rejectsWholeResponseForUnknownIdentifiers() {
        for (answer in listOf("[0]", "[999]", "[1, 4]", "[-1]"))
            assertEquals(emptyList<Int>(), recommendationIndices(answer, 3))
    }
    @Test fun rejectsEchoedCatalogAndExplanation() {
        for (answer in listOf("[1] Naruto [2] K-On!", "Answer: [2]", "[2] [1]"))
            assertEquals(emptyList<Int>(), recommendationIndices(answer, 3))
    }
    @Test fun rejectsMoreThanThreeSelections() {
        assertEquals(emptyList<Int>(), recommendationIndices("[4,3,2,1]", 4))
    }
    @Test fun emptySelectionAndFreeTextCreateNoTargets() {
        for (answer in listOf("[]", "Naruto; javascript:alert(1); https://example.com"))
            assertEquals(emptyList<Int>(), recommendationIndices(answer, 12))
        assertEquals(emptyList<Int>(), recommendationIndices("[1]", 0))
    }
}
