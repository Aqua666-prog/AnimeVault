package com.sergey.animevault.ui.online

import org.junit.Assert.*
import org.junit.Test

class CatalogRequestGenerationTest {
    @Test fun sameQueryRefreshRejectsEarlierResponse() {
        assertFalse(catalogRequestIsCurrent(1, 2, "all", "all", "Bleach", "Bleach"))
        assertTrue(catalogRequestIsCurrent(2, 2, "all", "all", "Bleach", "Bleach"))
    }
    @Test fun returnToPreviousQueryDoesNotAcceptItsOldResponse() {
        assertFalse(catalogRequestIsCurrent(1, 3, "kodik", "kodik", "A", "A"))
    }
    @Test fun providerAndQueryChangesInvalidateResponse() {
        assertFalse(catalogRequestIsCurrent(1, 1, "kodik", "animevost", "A", "A"))
        assertFalse(catalogRequestIsCurrent(1, 1, "kodik", "kodik", "A", "B"))
    }
}
