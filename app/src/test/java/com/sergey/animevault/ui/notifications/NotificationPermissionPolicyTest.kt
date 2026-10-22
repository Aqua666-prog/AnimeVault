package com.sergey.animevault.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    private fun action(
        enabled: Boolean = false,
        requiresRuntime: Boolean = true,
        runtimeGranted: Boolean = false,
        deniedOnce: Boolean = false,
        rationale: Boolean = false,
    ) = notificationPermissionAction(
        notificationsEnabled = enabled,
        runtimePermissionRequired = requiresRuntime,
        runtimePermissionGranted = runtimeGranted,
        hasConfirmedDenial = deniedOnce,
        shouldShowRationale = rationale,
    )

    @Test
    fun firstLaunch_requestsSystemPermission() {
        assertEquals(NotificationPermissionAction.REQUEST_SYSTEM, action())
        assertEquals(NotificationPermissionAction.NONE, action(enabled = true))
    }

    @Test
    fun firstDenial_allowsAnotherSystemRequest_withoutSendingToSettings() {
        val deniedOnce = confirmedDenialAfterResult(
            previouslyDenied = false,
            granted = false,
            shouldShowRationale = true,
        )
        assertTrue(deniedOnce)
        assertEquals(
            NotificationPermissionAction.REQUEST_SYSTEM,
            action(deniedOnce = deniedOnce, rationale = true),
        )
    }

    @Test
    fun swipeAway_doesNotCountAsDenial_andCanRetry() {
        val deniedOnce = confirmedDenialAfterResult(
            previouslyDenied = false,
            granted = false,
            shouldShowRationale = false,
        )
        assertFalse(deniedOnce)
        assertEquals(NotificationPermissionAction.REQUEST_SYSTEM, action(deniedOnce = deniedOnce))
    }

    @Test
    fun dismissalAfterOneDenial_preservesRationale_andRetry() {
        val deniedOnce = confirmedDenialAfterResult(
            previouslyDenied = true,
            granted = false,
            shouldShowRationale = true,
        )
        assertEquals(
            NotificationPermissionAction.REQUEST_SYSTEM,
            action(deniedOnce = deniedOnce, rationale = true),
        )
    }

    @Test
    fun permanentDenial_opensSettingsOnlyOnNextExplicitRequest() {
        val deniedOnce = confirmedDenialAfterResult(
            previouslyDenied = true,
            granted = false,
            shouldShowRationale = false,
        )
        assertTrue(deniedOnce)
        assertEquals(
            NotificationPermissionAction.OPEN_SETTINGS,
            action(deniedOnce = deniedOnce, rationale = false),
        )
    }

    @Test
    fun returningFromSettings_withPermissionEnabled_updatesStatus() {
        assertEquals(
            NotificationPermissionAction.NONE,
            action(enabled = true, deniedOnce = true, rationale = false),
        )
        assertFalse(
            confirmedDenialAfterResult(
                previouslyDenied = true,
                granted = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun devicesBeforeAndroid13_andAlreadyGrantedRuntimePermission_useSettings() {
        assertEquals(
            NotificationPermissionAction.OPEN_SETTINGS,
            action(requiresRuntime = false),
        )
        assertEquals(
            NotificationPermissionAction.OPEN_SETTINGS,
            action(runtimeGranted = true),
        )
    }
}
