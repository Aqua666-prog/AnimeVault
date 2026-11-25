package com.sergey.animevault.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRemovalPolicyTest {
    @Test fun ordinaryDeletionsProceed() {
        assertFalse(requiresScanRemovalConfirmation(previousCount = 1, missingCount = 1))
        assertFalse(requiresScanRemovalConfirmation(previousCount = 10, missingCount = 4))
        assertFalse(requiresScanRemovalConfirmation(previousCount = 101, missingCount = 50))
    }

    @Test fun bulkLossNeedsASeparateConfirmation() {
        assertTrue(requiresScanRemovalConfirmation(previousCount = 2, missingCount = 2))
        assertTrue(requiresScanRemovalConfirmation(previousCount = 10, missingCount = 5))
        assertTrue(requiresScanRemovalConfirmation(previousCount = 100, missingCount = 100))
    }
}
