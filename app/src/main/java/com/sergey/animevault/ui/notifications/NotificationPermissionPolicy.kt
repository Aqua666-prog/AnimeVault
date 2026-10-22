package com.sergey.animevault.ui.notifications

/**
 * Android does not distinguish a dismissed permission dialog from a denial in
 * RequestPermission's Boolean callback. A rationale appearing after the request
 * is the evidence that the user actually denied it once.
 */
internal enum class NotificationPermissionAction {
    NONE,
    REQUEST_SYSTEM,
    OPEN_SETTINGS,
}

internal fun notificationPermissionAction(
    notificationsEnabled: Boolean,
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: Boolean,
    hasConfirmedDenial: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionAction = when {
    notificationsEnabled -> NotificationPermissionAction.NONE
    !runtimePermissionRequired || runtimePermissionGranted -> NotificationPermissionAction.OPEN_SETTINGS
    hasConfirmedDenial && !shouldShowRationale -> NotificationPermissionAction.OPEN_SETTINGS
    else -> NotificationPermissionAction.REQUEST_SYSTEM
}

/** Dismissal does not change history; a grant clears the previous denial. */
internal fun confirmedDenialAfterResult(
    previouslyDenied: Boolean,
    granted: Boolean,
    shouldShowRationale: Boolean,
): Boolean = when {
    granted -> false
    shouldShowRationale -> true
    else -> previouslyDenied
}
